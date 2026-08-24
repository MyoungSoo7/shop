-- V20260616170000: settlement_payment_view 확장 (ADR 0020 Phase 3b-4)
--
-- QueryDSL 검색/대사·ES 색인 컷오버에 필요한 결제 필드 보강:
--   payment_method, refunded_amount(환불 누계), pg_transaction_id
-- payment_method/pg_transaction_id 는 PaymentCaptured 이벤트로, refunded_amount 는 PaymentRefunded 이벤트로 채운다.

ALTER TABLE opslab.settlement_payment_view
    ADD COLUMN IF NOT EXISTS payment_method    VARCHAR(40);
ALTER TABLE opslab.settlement_payment_view
    ADD COLUMN IF NOT EXISTS refunded_amount   NUMERIC(15, 2) NOT NULL DEFAULT 0;
ALTER TABLE opslab.settlement_payment_view
    ADD COLUMN IF NOT EXISTS pg_transaction_id VARCHAR(100);
