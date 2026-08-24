-- 포인트 선점 (docs/plan/point-ledger.md Phase 2).
--
-- 가상계좌는 입금 전까지 결제가 확정되지 않는다. 그 사이 포인트를 차감하지 않으면 같은 포인트를
-- 다른 주문에 또 쓸 수 있고, 차감해 버리면 미입금 취소마다 복원 경로가 필요하다. 선점은 그 사이를
-- 메운다 — point_accounts.locked 로 잠그고, 결말이 정해지는 순간(입금 확인 / 기한 경과 / 주문 취소)에
-- 한 번만 움직인다. locked 컬럼은 Phase 1 부터 있었고(항상 0), 여기서 처음으로 0 이 아니게 된다.
--
-- 이 테이블은 원장(point_entries)이 아니다. 엔트리는 총액 변동의 기록인데 선점은 총액을 바꾸지
-- 않는다(가용 → 잠금 이동일 뿐). 여기에 새 엔트리 유형을 만들면 DB CHECK·3자 대조 SQL·이벤트
-- 계약 5종을 함께 고쳐야 하므로, 선점의 감사 흔적은 이 테이블 자신이 진다.
--
-- 시각 타입은 point_ledger 와 같이 TIMESTAMPTZ 로 간다.

CREATE TABLE point_holds (
    id              BIGSERIAL      PRIMARY KEY,
    account_id      BIGINT         NOT NULL REFERENCES point_accounts(id),
    amount          NUMERIC(19,2)  NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    reference_type  VARCHAR(50)    NOT NULL,
    reference_id    VARCHAR(100)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,

    -- 같은 근거(결제 tender)로 선점이 두 번 생기는 것을 DB 가 막는다(Triple Idempotency L3).
    -- tender 는 결제 전체에서 유일하므로 account_id 를 키에 넣지 않는다 — 넣으면 같은 tender 로
    -- 다른 계정에 선점을 하나 더 만들 수 있게 되고, 그건 어떤 경우에도 옳지 않다.
    CONSTRAINT uq_point_holds_reference UNIQUE (reference_type, reference_id),
    CONSTRAINT chk_point_holds_status
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_point_holds_amount_positive CHECK (amount > 0),
    -- 포인트는 1원 단위 정수만 유통한다(point_accounts 와 같은 규약).
    CONSTRAINT chk_point_holds_integral CHECK (amount = trunc(amount)),
    -- 해소 시각과 상태는 함께 움직인다. 한쪽만 채워진 행은 "풀렸는데 언제인지 모른다"거나
    -- "아직 잡고 있는데 해소 시각이 있다"는 뜻이고, 둘 다 경합을 사후 재구성할 수 없게 만든다.
    CONSTRAINT chk_point_holds_resolved_pairing CHECK (
        (status = 'ACTIVE'  AND resolved_at IS NULL)
     OR (status <> 'ACTIVE' AND resolved_at IS NOT NULL)
    )
);

-- 계정의 활성 선점 합계를 묻는 질의(3자 대조·콘솔)용 부분 인덱스. 해소된 행은 대부분이 되므로
-- 전체 인덱스는 쓸모에 비해 크다.
CREATE INDEX idx_point_holds_active_account
    ON point_holds (account_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE point_holds IS
    '포인트 선점. 입금 대기 결제가 붙잡아 두는 잔고(point_accounts.locked)의 내역. 원장이 아니라 감사 흔적 — 선점은 총액을 바꾸지 않으므로 point_entries 를 남기지 않는다.';
COMMENT ON COLUMN point_holds.reference_id IS
    '선점 근거의 식별자. 결제 경로에서는 payment_tenders.id — 확정·해제 시 이 값으로 되찾는다.';
