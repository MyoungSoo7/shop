-- V30: DONE 정산의 금액 컬럼 UPDATE 차단 트리거
-- 이유: 판매자에게 지급 완료된 정산을 애플리케이션 버그로 수정하는 것을 DB 레벨에서 방어.
--      환불 반영은 settlement_adjustments 에 음수 레코드로 남겨야 원장이 무너지지 않는다.

CREATE OR REPLACE FUNCTION opslab.enforce_settlement_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'DONE' THEN
        IF NEW.payment_amount IS DISTINCT FROM OLD.payment_amount
           OR NEW.commission      IS DISTINCT FROM OLD.commission
           OR NEW.net_amount      IS DISTINCT FROM OLD.net_amount
           OR NEW.refunded_amount IS DISTINCT FROM OLD.refunded_amount
           OR NEW.status          IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION
                'Settlement id=% is DONE (immutable). Financial fields cannot be modified; use settlement_adjustments instead.',
                OLD.id
                USING ERRCODE = '23514';  -- check_violation
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_settlements_immutability ON opslab.settlements;
CREATE TRIGGER trg_settlements_immutability
    BEFORE UPDATE ON opslab.settlements
    FOR EACH ROW
    EXECUTE FUNCTION opslab.enforce_settlement_immutability();

COMMENT ON FUNCTION opslab.enforce_settlement_immutability() IS
    'DONE 상태 정산의 금액·상태 컬럼 UPDATE 차단. 환불은 settlement_adjustments 로만 기록.';
