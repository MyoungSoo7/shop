-- 정산 상태 레거시 값 정규화
-- 2026-04-22: SettlementStatus enum 에서 레거시 값 제거를 위해 기존 DB 데이터를 새 상태 머신(REQUESTED/PROCESSING/DONE/FAILED/CANCELED)으로 일괄 매핑

UPDATE opslab.settlements SET status = 'DONE'       WHERE status = 'CONFIRMED';
UPDATE opslab.settlements SET status = 'REQUESTED'  WHERE status = 'PENDING';
UPDATE opslab.settlements SET status = 'PROCESSING' WHERE status = 'WAITING_APPROVAL';
UPDATE opslab.settlements SET status = 'DONE'       WHERE status = 'APPROVED';
UPDATE opslab.settlements SET status = 'FAILED'     WHERE status = 'REJECTED';
UPDATE opslab.settlements SET status = 'REQUESTED'  WHERE status = 'CALCULATED';

-- 승인 상태 partial index 는 의미 없는 값이 돼 제거
DROP INDEX IF EXISTS opslab.idx_settlements_approval_status;
