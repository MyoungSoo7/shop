-- V43: Payout (출금) — 정산금 → 셀러 계좌 실제 송금
--
-- 정산 사이클의 종착점. Holdback 해제된 Settlement 가 Payout 으로 변환되어
-- 펌뱅킹 API 호출로 셀러 통장에 입금된다.
--
-- 상태 머신:
--   REQUESTED → SENDING → COMPLETED
--                       → FAILED → (운영자 재시도) → REQUESTED
--                       → CANCELED (운영자 취소)
--
-- 일/셀러별 송금 한도는 PayoutLimitChecker 가 검증. 한도 초과 시 다음 영업일로 분배.
--
-- 보안: bank_account_number 는 운영에서 KMS 암호화 권장. 본 포트폴리오는 평문 (스키마만 정의).

CREATE TABLE IF NOT EXISTS opslab.payouts (
    id                          BIGSERIAL PRIMARY KEY,
    settlement_id               BIGINT,                          -- 1 payout = 1 settlement (단순화). 수동 송금은 NULL
    seller_id                   BIGINT       NOT NULL,
    amount                      NUMERIC(12, 2) NOT NULL,

    -- 송금 대상 계좌 (정산 시점 스냅샷 — 추후 셀러가 변경해도 이력 보존)
    bank_code                   VARCHAR(10)  NOT NULL,           -- KB / SHINHAN / WOORI / TOSS / KAKAO 등
    bank_account_number         VARCHAR(50)  NOT NULL,           -- 운영 시 KMS 암호화 권장
    account_holder_name         VARCHAR(100) NOT NULL,

    status                      VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    firm_banking_transaction_id VARCHAR(100),                    -- 펌뱅킹 거래 ID
    failure_reason              TEXT,
    retry_count                 INTEGER      NOT NULL DEFAULT 0,
    operator_id                 VARCHAR(100),                    -- 수동 처리 시 운영자

    requested_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at                     TIMESTAMP,
    completed_at                TIMESTAMP,
    failed_at                   TIMESTAMP,
    created_at                  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payouts_settlement
        FOREIGN KEY (settlement_id) REFERENCES opslab.settlements(id) ON DELETE SET NULL,
    CONSTRAINT chk_payouts_amount
        CHECK (amount > 0),
    CONSTRAINT chk_payouts_status
        CHECK (status IN ('REQUESTED', 'SENDING', 'COMPLETED', 'FAILED', 'CANCELED'))
);

-- 같은 정산은 1번만 payout 생성 — 중복 송금 방지 (가장 위험한 사고)
CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_settlement
    ON opslab.payouts (settlement_id)
    WHERE settlement_id IS NOT NULL;

-- 운영자 콘솔 — FAILED 모니터링
CREATE INDEX IF NOT EXISTS idx_payouts_status
    ON opslab.payouts (status, requested_at);

-- 셀러별 일별 한도 검증용
CREATE INDEX IF NOT EXISTS idx_payouts_seller_date
    ON opslab.payouts (seller_id, completed_at)
    WHERE status = 'COMPLETED';

COMMENT ON TABLE opslab.payouts IS
    '정산금 → 셀러 계좌 송금. Settlement DONE + Holdback released 시 자동 생성, 펌뱅킹 호출.';
COMMENT ON COLUMN opslab.payouts.firm_banking_transaction_id IS
    '펌뱅킹 거래 ID — COMPLETED 시 보존하여 사후 추적·환수 가능';
COMMENT ON COLUMN opslab.payouts.bank_account_number IS
    '운영 환경: 반드시 KMS column-level encryption. 본 포트폴리오는 스키마만 정의.';
