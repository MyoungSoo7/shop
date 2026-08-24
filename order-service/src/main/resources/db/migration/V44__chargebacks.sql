-- V44: Chargeback (카드사 분쟁) 도메인.
--
-- 환불(Refund)과 분리되는 이유:
--   - Refund    = 고객이 셀러에 환불 요청 (셀러 합의 또는 자동 환불 정책)
--   - Chargeback = 카드사가 고객 신고 받아 강제로 결제 취소 (PG 가 정산금에서 차감)
--   회계상 별도 원장으로 추적해야 한다 (분쟁 사유 코드, 셀러 환수, 사후 통계).
--
-- 통합점:
--   - settlement_adjustments 에 chargeback_id 컬럼 추가 (refund_id 와 양립). nullable, FK.
--   - ACCEPTED chargeback 한 건 = settlement_adjustments 음수 row 한 건 (1:1).
--   - 이미 Payout COMPLETED 된 경우는 별도 ReversePayout (Phase 6).

-- 1) chargebacks 본 테이블
CREATE TABLE IF NOT EXISTS opslab.chargebacks (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      BIGINT      NOT NULL,
    settlement_id   BIGINT,                     -- 정산 생성 전 발생 가능 (PG 기준)
    amount          DECIMAL(10, 2) NOT NULL,
    -- 카드사 분쟁 사유 코드 (FRAUD/DUPLICATE/NOT_RECEIVED/PRODUCT_NOT_AS_DESCRIBED/OTHER).
    reason_code     VARCHAR(50) NOT NULL,
    reason_detail   TEXT,
    -- 상태 머신: OPEN → ACCEPTED | REJECTED (둘 다 종료 상태).
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    -- 분쟁 출처: PG 통지 webhook 인지 운영자 수동 등록인지.
    source          VARCHAR(20) NOT NULL,       -- PG_WEBHOOK | MANUAL
    pg_chargeback_id VARCHAR(128),              -- 멱등 — PG 가 부여한 분쟁 ID
    decided_by      VARCHAR(255),               -- 결정한 운영자 (감사용)
    decision_note   TEXT,                       -- 결정 근거 (수락 사유, 기각 증빙 등)
    raised_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    decided_at      TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_chargeback_payment    FOREIGN KEY (payment_id)    REFERENCES opslab.payments(id),
    CONSTRAINT fk_chargeback_settlement FOREIGN KEY (settlement_id) REFERENCES opslab.settlements(id),
    CONSTRAINT chk_chargebacks_amount   CHECK (amount > 0),
    CONSTRAINT chk_chargebacks_status   CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_chargebacks_source   CHECK (source IN ('PG_WEBHOOK', 'MANUAL'))
);

-- 멱등 — 같은 PG 분쟁 ID 가 두 번 들어와도 OPEN 한 건만 생성.
-- pg_chargeback_id 가 NULL 인 MANUAL 건은 중복 방지 책임이 운영자에게 있음.
CREATE UNIQUE INDEX IF NOT EXISTS idx_chargebacks_pg_id_unique
    ON opslab.chargebacks (pg_chargeback_id)
    WHERE pg_chargeback_id IS NOT NULL;

-- 조회 패턴 인덱스
CREATE INDEX IF NOT EXISTS idx_chargebacks_payment_id    ON opslab.chargebacks (payment_id);
CREATE INDEX IF NOT EXISTS idx_chargebacks_settlement_id ON opslab.chargebacks (settlement_id);
CREATE INDEX IF NOT EXISTS idx_chargebacks_status_raised ON opslab.chargebacks (status, raised_at DESC);

COMMENT ON TABLE  opslab.chargebacks IS '카드사 분쟁(chargeback) 원장. 환불과 별도 회계 처리. ADR 작성 예정.';
COMMENT ON COLUMN opslab.chargebacks.amount     IS '분쟁 금액. 항상 양수. 정산 차감 시 settlement_adjustments 에 음수로 기록.';
COMMENT ON COLUMN opslab.chargebacks.source     IS 'PG_WEBHOOK = PG 통지 자동 등록, MANUAL = 운영자 수동 등록';
COMMENT ON COLUMN opslab.chargebacks.pg_chargeback_id IS 'PG 측 분쟁 ID. webhook 멱등 키.';

-- 2) settlement_adjustments 에 chargeback_id 컬럼 추가
-- refund_id 와 양립. 둘 중 하나만 채워지면 됨 (CHECK 으로 강제).
ALTER TABLE opslab.settlement_adjustments
    ADD COLUMN IF NOT EXISTS chargeback_id BIGINT;

ALTER TABLE opslab.settlement_adjustments
    ADD CONSTRAINT fk_adjustment_chargeback
    FOREIGN KEY (chargeback_id) REFERENCES opslab.chargebacks(id);

-- 환불 1 건 또는 분쟁 1 건만 연결. 둘 다 NULL 인 미연결 음수 row 는 V4 컨벤션상 가능하나
-- 신규 row 는 적어도 한쪽 채우도록 강제 (NOT VALID 옵션은 기존 NULL row 보호).
ALTER TABLE opslab.settlement_adjustments
    ADD CONSTRAINT chk_adjustment_refund_xor_chargeback
    CHECK (
        (refund_id IS NULL AND chargeback_id IS NULL)
        OR (refund_id IS NOT NULL AND chargeback_id IS NULL)
        OR (refund_id IS NULL AND chargeback_id IS NOT NULL)
    ) NOT VALID;

-- 분쟁 1 건당 조정 1 건 보장 (refund 와 동일 컨벤션)
CREATE UNIQUE INDEX IF NOT EXISTS idx_adjustments_chargeback_id_unique
    ON opslab.settlement_adjustments (chargeback_id)
    WHERE chargeback_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_adjustments_chargeback_id
    ON opslab.settlement_adjustments (chargeback_id);

COMMENT ON COLUMN opslab.settlement_adjustments.chargeback_id IS '카드사 분쟁 연결. refund_id 와 양립 (둘 중 하나만 채워짐).';
