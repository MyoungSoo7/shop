-- 기프트카드 원장 (docs/plan/gift-card-ledger.md Phase 1).
--
-- 포인트 원장을 닫으면서 GIFT_CARD 텐더는 같은 구멍으로 남겨 두었다. 이 마이그레이션이 그 장부를 만든다.
--
-- 포인트와 가장 다른 점: **잔액 요약 테이블이 없다.** 잔액은 증서(카드) 하나에 붙고, 사용자 잔액은
-- Σ(등록된 ACTIVE 카드 remaining) 으로 계산한다. 요약을 저장하지 않으므로 "요약과 상세가 어긋나는"
-- 종류의 데이터 손상이 구조적으로 불가능하고, 불변식이 카드 한 행으로 닫힌다.
--
-- 시각은 TIMESTAMPTZ — 소멸 시각은 고객 재산이 사라지는 순간이라 서버 타임존에 의존하면 안 된다.

-- ── gift_cards ───────────────────────────────────────────────────────────────
CREATE TABLE gift_cards (
    id                BIGSERIAL      PRIMARY KEY,
    code_hash         VARCHAR(64)    NOT NULL,
    code_last4        VARCHAR(4)     NOT NULL,
    face_amount       NUMERIC(19,2)  NOT NULL,
    remaining_amount  NUMERIC(19,2)  NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'ISSUED',
    owner_user_id     BIGINT,
    issued_at         TIMESTAMPTZ    NOT NULL,
    activated_at      TIMESTAMPTZ,
    registered_at     TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ    NOT NULL,
    issued_by         VARCHAR(64)    NOT NULL,
    memo              VARCHAR(255),
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- 코드는 평문으로 저장하지 않는다. 해시가 곧 조회 키이자 중복 발행 방어선이다.
    CONSTRAINT uq_gift_cards_code_hash UNIQUE (code_hash),
    CONSTRAINT chk_gift_cards_status
        CHECK (status IN ('ISSUED', 'ACTIVE', 'REGISTERED', 'USED_UP', 'EXPIRED', 'SUSPENDED')),
    CONSTRAINT chk_gift_cards_face_positive        CHECK (face_amount > 0),
    CONSTRAINT chk_gift_cards_remaining_range      CHECK (remaining_amount >= 0 AND remaining_amount <= face_amount),
    -- 상품권도 1원 단위 정수만 유통한다(포인트와 같은 규약).
    CONSTRAINT chk_gift_cards_integral
        CHECK (face_amount = trunc(face_amount) AND remaining_amount = trunc(remaining_amount)),
    CONSTRAINT chk_gift_cards_expiry_after_issue   CHECK (expires_at > issued_at),
    -- 귀속 정합: 등록 이후 상태에는 소유자가 있어야 하고, 등록 전에는 없어야 한다.
    -- 이게 없으면 "주인 없는 잔액"이나 "등록 안 됐는데 주인이 있는 카드"가 조용히 생긴다.
    CONSTRAINT chk_gift_cards_owner_matches_status
        CHECK (
            (status IN ('REGISTERED', 'USED_UP') AND owner_user_id IS NOT NULL AND registered_at IS NOT NULL)
         OR (status IN ('ISSUED', 'ACTIVE') AND owner_user_id IS NULL AND registered_at IS NULL)
         OR (status IN ('EXPIRED', 'SUSPENDED'))
        )
);

-- 사용 시 소비 순서(만료 임박 순) + 사용자 잔액 조회. 등록된 카드만 대상이라 부분 인덱스로 좁힌다.
CREATE INDEX idx_gift_cards_owner_usable
    ON gift_cards (owner_user_id, expires_at, id)
    WHERE status = 'REGISTERED' AND remaining_amount > 0;
-- 소멸 배치 스캔 — 아직 살아 있는 카드만 훑는다.
CREATE INDEX idx_gift_cards_expiring
    ON gift_cards (expires_at)
    WHERE status IN ('ACTIVE', 'REGISTERED');

COMMENT ON TABLE gift_cards IS
    '기프트카드 증서이자 잔액. 잔액 요약 테이블을 따로 두지 않는다 — 사용자 잔액은 Σ(REGISTERED 카드 remaining). code 는 평문 미저장(code_hash 만).';
COMMENT ON COLUMN gift_cards.code_hash IS
    '코드의 SHA-256. 코드 엔트로피가 80비트라 사전 공격이 성립하지 않으므로 솔트를 쓰지 않는다(솔트를 쓰면 조회에 전수 비교가 필요).';

-- ── gift_card_entries (append-only) ──────────────────────────────────────────
-- 포인트와 달리 배분 상세 테이블이 없다 — 엔트리 자체가 카드 단위이기 때문이다.
-- 한 결제가 여러 장을 걸치면 카드마다 엔트리가 하나씩 생긴다.
CREATE TABLE gift_card_entries (
    id              BIGSERIAL      PRIMARY KEY,
    gift_card_id    BIGINT         NOT NULL REFERENCES gift_cards(id),
    entry_type      VARCHAR(20)    NOT NULL,
    amount          NUMERIC(19,2)  NOT NULL,
    reference_type  VARCHAR(50)    NOT NULL,
    reference_id    VARCHAR(100)   NOT NULL,
    sequence        INTEGER        NOT NULL DEFAULT 0,
    memo            VARCHAR(255),
    created_by      VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_gift_card_entries_type
        CHECK (entry_type IN ('REGISTER', 'USE', 'RESTORE', 'EXPIRE')),
    -- 금액은 언제나 양수. 방향은 entry_type 이 결정한다(다른 원장과 같은 규약).
    CONSTRAINT chk_gift_card_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_gift_card_entries_integral CHECK (amount = trunc(amount)),
    CONSTRAINT uq_gift_card_entries_natural
        UNIQUE (gift_card_id, entry_type, reference_type, reference_id, sequence)
);

CREATE INDEX idx_gift_card_entries_card_created ON gift_card_entries (gift_card_id, created_at DESC);
-- 환불 복원이 "이 tender 로 어느 카드를 얼마나 썼는지" 되짚는 경로.
CREATE INDEX idx_gift_card_entries_reference
    ON gift_card_entries (entry_type, reference_type, reference_id);

COMMENT ON TABLE gift_card_entries IS
    'append-only 기프트카드 원장. 엔트리가 카드 단위라 포인트의 로트 배분 상세 테이블이 필요 없다. UNIQUE(gift_card_id, entry_type, reference_type, reference_id, sequence) 가 L3 멱등 방어선.';

-- ── ROLLBACK NOTES ───────────────────────────────────────────────────────────
-- DROP TABLE gift_card_entries;
-- DROP TABLE gift_cards;
-- (생성 역순 — FK 의존성 때문에 자식 테이블부터 제거해야 한다.)
