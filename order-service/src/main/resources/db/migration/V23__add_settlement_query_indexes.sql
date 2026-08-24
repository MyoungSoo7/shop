-- V22: QueryDSL 쿼리 최적화를 위한 복합 인덱스

-- 1. Cursor 기반 페이지네이션용 복합 인덱스
-- WHERE settlement_date <= ? AND id < ? ORDER BY settlement_date DESC, id DESC
CREATE INDEX IF NOT EXISTS idx_settlements_date_id_desc
ON settlements(settlement_date DESC, id DESC);

-- 2. 월별 집계용 인덱스 (status 포함, covering index)
-- GROUP BY settlement_date WHERE status = ? 쿼리에서 Index-Only Scan 가능
CREATE INDEX IF NOT EXISTS idx_settlements_date_status_amounts
ON settlements(settlement_date, status)
INCLUDE (payment_amount, commission, net_amount);

-- 3. 대사(Reconciliation) 쿼리용: payment_id로 settlement 조회 시 금액 포함
CREATE INDEX IF NOT EXISTS idx_settlements_payment_id_amounts
ON settlements(payment_id)
INCLUDE (payment_amount, net_amount, status);

-- 4. [제거] 승인 관련 레거시 상태 partial index 는 V26 에서 DROP

-- 5. payments 테이블: captured_at 기반 조회 최적화
CREATE INDEX IF NOT EXISTS idx_payments_captured_status_amount
ON payments(captured_at, status)
INCLUDE (amount, refunded_amount, order_id);
