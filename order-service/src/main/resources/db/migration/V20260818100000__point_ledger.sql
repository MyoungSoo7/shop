-- 포인트 원장 (docs/plan/point-ledger.md Phase 1).
--
-- TenderType.POINT 는 결제 수단으로 이미 열려 있으나 잔액 원장이 없어 차감·복원이 로그만 남기고
-- 지나갔다. 이 마이그레이션이 그 장부를 만든다.
--
-- 잔고 불변식(total = available + locked, 3필드 모두 >= 0)은 PointAccount.enforceInvariant() 가
-- 1차 방어선이고 아래 CHECK 가 최후 방어선이다 — deposit_accounts 와 동일한 2중 구조.
-- 포인트에만 있는 축은 로트(point_lots): 적립 1건마다 유효기간·출처가 다르므로 단일 풀로는
-- 소멸도 보너스 회수도 표현할 수 없다.
--
-- 시각 타입은 TIMESTAMPTZ 로 간다(order-service 기존 테이블의 TIMESTAMP 와 다름). 소멸 시각은
-- 고객 재산이 사라지는 순간이라 서버 타임존에 의존하면 안 된다.

-- ── point_accounts ───────────────────────────────────────────────────────────
CREATE TABLE point_accounts (
    id          BIGSERIAL      PRIMARY KEY,
    user_id     BIGINT         NOT NULL,
    available   NUMERIC(19,2)  NOT NULL DEFAULT 0,
    locked      NUMERIC(19,2)  NOT NULL DEFAULT 0,
    total       NUMERIC(19,2)  NOT NULL DEFAULT 0,
    status      VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_point_accounts_user UNIQUE (user_id),
    CONSTRAINT chk_point_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_point_accounts_available_non_negative CHECK (available >= 0),
    CONSTRAINT chk_point_accounts_locked_non_negative    CHECK (locked    >= 0),
    CONSTRAINT chk_point_accounts_total_non_negative     CHECK (total     >= 0),
    CONSTRAINT chk_point_accounts_total_eq_sum           CHECK (total = available + locked),
    -- 포인트는 1원 단위 정수만 유통한다. 0.5 포인트가 유입되면 이후 절사 규칙이 전부 흔들린다.
    CONSTRAINT chk_point_accounts_integral
        CHECK (available = trunc(available) AND locked = trunc(locked) AND total = trunc(total))
);

COMMENT ON TABLE point_accounts IS
    '구매자 포인트 계정. 잔고 불변식(total=available+locked, 3필드 >=0)은 PointAccount 가 1차, DB CHECK 가 최후 방어선. locked 는 Phase 2(입금대기 선점)까지 항상 0.';

-- ── point_lots ───────────────────────────────────────────────────────────────
-- 적립 1건 = 로트 1개. 사용은 로트를 만료임박 순으로 먹고, 소멸은 로트 단위로 일어난다.
CREATE TABLE point_lots (
    id                BIGSERIAL      PRIMARY KEY,
    account_id        BIGINT         NOT NULL REFERENCES point_accounts(id),
    origin            VARCHAR(24)    NOT NULL,
    original_amount   NUMERIC(19,2)  NOT NULL,
    remaining_amount  NUMERIC(19,2)  NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    granted_at        TIMESTAMPTZ    NOT NULL,
    expires_at        TIMESTAMPTZ,
    reference_type    VARCHAR(50)    NOT NULL,
    reference_id      VARCHAR(100)   NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,

    -- 같은 근거로 로트가 두 번 발급되는 것을 DB 가 막는다(Triple Idempotency L3).
    CONSTRAINT uq_point_lots_natural UNIQUE (account_id, origin, reference_type, reference_id),
    CONSTRAINT chk_point_lots_origin
        CHECK (origin IN ('CHARGE_PRINCIPAL', 'CHARGE_BONUS', 'ORDER_EARN', 'MANUAL_GRANT', 'REFUND_RESTORE')),
    CONSTRAINT chk_point_lots_status
        CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT chk_point_lots_original_positive       CHECK (original_amount > 0),
    CONSTRAINT chk_point_lots_remaining_non_negative  CHECK (remaining_amount >= 0),
    CONSTRAINT chk_point_lots_remaining_le_original   CHECK (remaining_amount <= original_amount),
    CONSTRAINT chk_point_lots_expiry_after_grant
        CHECK (expires_at IS NULL OR expires_at > granted_at),
    CONSTRAINT chk_point_lots_integral
        CHECK (original_amount = trunc(original_amount) AND remaining_amount = trunc(remaining_amount))
);

-- 사용 시 소비 순서 결정. 만료 임박 순, 무기한(NULL)은 마지막.
CREATE INDEX idx_point_lots_consume
    ON point_lots (account_id, expires_at NULLS LAST, id) WHERE status = 'ACTIVE';
-- 소멸 배치 스캔용 — 무기한 로트는 스캔 대상이 아니라 색인에서 제외.
CREATE INDEX idx_point_lots_expiring
    ON point_lots (expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

COMMENT ON TABLE point_lots IS
    '포인트 적립 로트. 유효기간·출처가 건별로 다르므로 잔고는 ACTIVE 로트 remaining 의 합이다. UNIQUE(account_id, origin, reference_type, reference_id) 가 L3 멱등 방어선.';

-- ── point_entries (append-only) ──────────────────────────────────────────────
-- 모든 잔고 변경은 이 테이블에 엔트리 한 건으로 기록되어야 한다(대사 가능성).
CREATE TABLE point_entries (
    id              BIGSERIAL      PRIMARY KEY,
    account_id      BIGINT         NOT NULL REFERENCES point_accounts(id),
    entry_type      VARCHAR(20)    NOT NULL,
    amount          NUMERIC(19,2)  NOT NULL,
    reference_type  VARCHAR(50)    NOT NULL,
    reference_id    VARCHAR(100)   NOT NULL,
    sequence        INTEGER        NOT NULL DEFAULT 0,
    memo            VARCHAR(255),
    created_by      VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_point_entries_type
        CHECK (entry_type IN ('GRANT', 'USE', 'RESTORE', 'EXPIRE', 'REVOKE')),
    -- 금액은 언제나 양수. 방향은 entry_type 이 결정한다(deposit_entries 와 동일 규약).
    CONSTRAINT chk_point_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_point_entries_integral CHECK (amount = trunc(amount)),
    CONSTRAINT uq_point_entries_natural
        UNIQUE (account_id, entry_type, reference_type, reference_id, sequence)
);

CREATE INDEX idx_point_entries_account_created ON point_entries (account_id, created_at DESC);

COMMENT ON TABLE point_entries IS
    'append-only 포인트 원장. UNIQUE(account_id, entry_type, reference_type, reference_id, sequence) 가 L3 멱등 방어선.';

-- ── point_lot_consumptions ───────────────────────────────────────────────────
-- 엔트리 1건이 어느 로트를 얼마나 소비했는지. 환불 복원이 "원래 그 로트"로 돌아가는 근거.
CREATE TABLE point_lot_consumptions (
    id        BIGSERIAL      PRIMARY KEY,
    entry_id  BIGINT         NOT NULL REFERENCES point_entries(id),
    lot_id    BIGINT         NOT NULL REFERENCES point_lots(id),
    amount    NUMERIC(19,2)  NOT NULL,

    CONSTRAINT uq_point_lot_consumptions UNIQUE (entry_id, lot_id),
    CONSTRAINT chk_point_lot_consumptions_positive CHECK (amount > 0)
);

CREATE INDEX idx_point_lot_consumptions_lot ON point_lot_consumptions (lot_id);

COMMENT ON TABLE point_lot_consumptions IS
    '엔트리별 로트 소비 상세. 환불 복원 시 원 로트를 찾는 근거이자, 잔고와 로트 합계의 대사 자료.';

-- ── point_earn_policy (ADR 0032 구조 재사용) ─────────────────────────────────
-- 적립률을 코드 상수가 아니라 "기간을 가진 데이터"로 둔다. 표가 비면 적립 0 — 무행동 착지.
--
-- EXCLUDE 에 equality 열(scope, scope_key)을 섞으려면 btree_gist 가 필요하다. settlement DB 에는
-- ADR 0032 가 이미 깔았지만 opslab 은 별도 DB 라 여기서 다시 선언해야 한다.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE point_earn_policy (
    id              BIGSERIAL    PRIMARY KEY,
    scope           VARCHAR(16)  NOT NULL,
    scope_key       VARCHAR(64)  NOT NULL,
    earn_rate       NUMERIC(6,5) NOT NULL,
    validity_days   INTEGER      NOT NULL,
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    reason          VARCHAR(255) NOT NULL,
    created_by      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ,

    CONSTRAINT chk_pep_scope    CHECK (scope IN ('GLOBAL', 'GRADE', 'CATEGORY')),
    CONSTRAINT chk_pep_rate     CHECK (earn_rate >= 0 AND earn_rate <= 1),
    CONSTRAINT chk_pep_validity CHECK (validity_days > 0),
    CONSTRAINT chk_pep_range    CHECK (effective_to IS NULL OR effective_to > effective_from),

    -- 같은 (scope, scope_key) 안에서 기간이 겹치면 어느 적립률이 맞는지 설명할 수 없다.
    -- 우선순위로 푸는 대신 입력 시점에 DB 가 막는다. [from, to) 반열림이라 경계 접촉은 중첩이 아니다.
    CONSTRAINT ex_pep_no_overlap EXCLUDE USING gist (
        scope     WITH =,
        scope_key WITH =,
        daterange(effective_from, COALESCE(effective_to, DATE '9999-12-31'), '[)') WITH &&
    )
);

COMMENT ON TABLE point_earn_policy IS
    '포인트 적립률 유효기간 정책 (ADR 0032 구조 재사용) — 행 UPDATE 금지, 변경은 close + 신규 행. 표가 비면 적립률 0.';
COMMENT ON COLUMN point_earn_policy.reason IS '적립률 근거 — 감사 추적용 필수 입력';

-- ── ROLLBACK NOTES ───────────────────────────────────────────────────────────
-- DROP TABLE point_earn_policy;
-- DROP TABLE point_lot_consumptions;
-- DROP TABLE point_entries;
-- DROP TABLE point_lots;
-- DROP TABLE point_accounts;
-- (생성 역순 — FK 의존성 때문에 자식 테이블부터 제거해야 한다.)
