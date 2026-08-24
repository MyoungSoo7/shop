-- V20260715200006: 금융 FK 의 삭제 의미를 RESTRICT 로 명시 재생성
--
-- 배경(DB 설계 리뷰 지적):
--   V2/V4 의 금융 FK(주문↔결제↔정산↔환불)는 ON DELETE 를 지정하지 않아 기본값 NO ACTION 이다.
--   NO ACTION 은 (문 종료 시점 검사) 삭제를 막긴 하지만, "이 부모 금융 레코드는 자식이 있는 한
--   절대 삭제 불가" 라는 의도가 스키마에 드러나지 않는다. 즉시 검사·의도 명시를 위해 RESTRICT 로
--   재생성한다(정산/원장 무결성상 결제·주문·정산 부모 행은 물리 삭제 대상이 아니다 — 취소는 상태로).
--
-- 안전성: 재생성은 저장소 전반 패턴대로 ADD CONSTRAINT ... NOT VALID 후 VALIDATE CONSTRAINT 2단으로
--   수행한다 — 즉시 검증 ADD 는 대용량 opslab 에서 전수 스캔 + ACCESS EXCLUSIVE 장기 락을 유발한다.
--   NOT VALID 는 신규 쓰기부터 즉시 강제하고, VALIDATE 는 SHARE UPDATE EXCLUSIVE 로 병행 쓰기를 허용한다.
--   컬럼·방향 변경 없음(제약 옵션만 명시) → ddl-auto=validate 영향 없음.

-- payments.order_id → orders.id
ALTER TABLE opslab.payments
    DROP CONSTRAINT IF EXISTS fk_payment_order;
ALTER TABLE opslab.payments
    ADD CONSTRAINT fk_payment_order
        FOREIGN KEY (order_id) REFERENCES opslab.orders(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE opslab.payments VALIDATE CONSTRAINT fk_payment_order;
COMMENT ON CONSTRAINT fk_payment_order ON opslab.payments IS
    '결제가 붙은 주문은 삭제 불가(RESTRICT). 주문 취소는 status 로만 표현.';

-- settlements.payment_id → payments.id
ALTER TABLE opslab.settlements
    DROP CONSTRAINT IF EXISTS fk_settlement_payment;
ALTER TABLE opslab.settlements
    ADD CONSTRAINT fk_settlement_payment
        FOREIGN KEY (payment_id) REFERENCES opslab.payments(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE opslab.settlements VALIDATE CONSTRAINT fk_settlement_payment;
COMMENT ON CONSTRAINT fk_settlement_payment ON opslab.settlements IS
    '정산이 붙은 결제는 삭제 불가(RESTRICT). 정산 원장 무결성 보호.';

-- settlements.order_id → orders.id
ALTER TABLE opslab.settlements
    DROP CONSTRAINT IF EXISTS fk_settlement_order;
ALTER TABLE opslab.settlements
    ADD CONSTRAINT fk_settlement_order
        FOREIGN KEY (order_id) REFERENCES opslab.orders(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE opslab.settlements VALIDATE CONSTRAINT fk_settlement_order;
COMMENT ON CONSTRAINT fk_settlement_order ON opslab.settlements IS
    '정산이 붙은 주문은 삭제 불가(RESTRICT).';

-- refunds.payment_id → payments.id
ALTER TABLE opslab.refunds
    DROP CONSTRAINT IF EXISTS fk_refund_payment;
ALTER TABLE opslab.refunds
    ADD CONSTRAINT fk_refund_payment
        FOREIGN KEY (payment_id) REFERENCES opslab.payments(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE opslab.refunds VALIDATE CONSTRAINT fk_refund_payment;
COMMENT ON CONSTRAINT fk_refund_payment ON opslab.refunds IS
    '환불 이력이 붙은 결제는 삭제 불가(RESTRICT).';
