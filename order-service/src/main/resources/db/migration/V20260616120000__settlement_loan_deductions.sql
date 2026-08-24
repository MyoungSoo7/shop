-- V20260616120000: 정산별 선정산 대출 차감액 (loan → settlement saga 의 settlement 측 상태)
--
-- loan-service 가 정산 확정 시 미상환 대출을 차감하고 LoanRepaymentApplied{settlementId, deducted}
-- 를 발행한다. settlement 는 이를 수신해 정산건별 차감액을 기록하고, 해당 정산의 payout 시
-- 순지급액 = netAmount - deducted 로 지급한다.
--
-- settlement_id 를 PK 로 두어 LoanRepaymentApplied 중복 수신에도 멱등(차감 1회 반영).

CREATE TABLE IF NOT EXISTS opslab.settlement_loan_deductions (
    settlement_id  BIGINT         PRIMARY KEY,
    seller_id      BIGINT         NOT NULL,
    deducted       NUMERIC(19, 2) NOT NULL CHECK (deducted >= 0),
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);
