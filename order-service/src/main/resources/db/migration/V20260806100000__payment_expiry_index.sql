-- 미입금 결제 자동 만료 배치 지원 인덱스.
--
-- 구동 쿼리: SELECT ... FROM payments WHERE status = 'READY' AND created_at < :cutoff ORDER BY created_at
-- READY 는 전체 결제 중 소수(대부분 CAPTURED/REFUNDED)라 부분 인덱스가 크기·유지비 모두 유리하다.
-- 기존 idx_payments_status_updated_at(V3) 는 선두가 status 이나 정렬·범위 컬럼이 updated_at 이라
-- created_at 범위 스캔을 커버하지 못한다.

CREATE INDEX IF NOT EXISTS idx_payments_pending_expiry
    ON opslab.payments (created_at)
    WHERE status = 'READY';

COMMENT ON INDEX opslab.idx_payments_pending_expiry IS
    '미입금 만료 배치 — READY 결제의 생성시각 범위 스캔용 부분 인덱스';
