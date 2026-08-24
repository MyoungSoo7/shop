-- 정산 승인 프로세스를 위한 컬럼 추가

ALTER TABLE settlements
    ADD COLUMN approved_by BIGINT,
    ADD COLUMN approved_at TIMESTAMP,
    ADD COLUMN rejected_by BIGINT,
    ADD COLUMN rejected_at TIMESTAMP,
    ADD COLUMN rejection_reason VARCHAR(500);

-- 승인자/반려자 인덱스 추가 (관리자 대시보드 성능 개선)
CREATE INDEX idx_settlements_approved_by ON settlements(approved_by);
CREATE INDEX idx_settlements_rejected_by ON settlements(rejected_by);

-- 기존 데이터에 대한 마이그레이션 (선택적)
-- PENDING 상태를 WAITING_APPROVAL로 변경하려면 아래 주석 해제
-- UPDATE settlements SET status = 'WAITING_APPROVAL' WHERE status = 'PENDING';

COMMENT ON COLUMN settlements.approved_by IS '승인한 관리자 ID';
COMMENT ON COLUMN settlements.approved_at IS '승인 일시';
COMMENT ON COLUMN settlements.rejected_by IS '반려한 관리자 ID';
COMMENT ON COLUMN settlements.rejected_at IS '반려 일시';
COMMENT ON COLUMN settlements.rejection_reason IS '반려 사유';
