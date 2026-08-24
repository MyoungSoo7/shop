-- 역정산 감사 레코드 활성화: refund_id 를 nullable 로 완화
-- 이유: Refund 도메인 엔티티가 아직 코드화되지 않아 refund_id 를 채울 수 없음.
--       감사 추적을 먼저 보존하고, Refund 엔티티 도입 시 refund_id 를 백필하도록 한다.

ALTER TABLE opslab.settlement_adjustments
    ALTER COLUMN refund_id DROP NOT NULL;

COMMENT ON COLUMN opslab.settlement_adjustments.refund_id IS 'Refund 엔티티 도입 전 nullable 허용';
