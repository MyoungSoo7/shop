-- V20260616120000: settlement 소유 결제 프로젝션 (ADR 0020 Phase 2, CQRS read model)
--
-- settlement-service 가 order 의 payments 테이블을 @Immutable 로 직접 매핑하던 것을 대체하기 위한
-- 자체 프로젝션. PaymentCaptured 이벤트를 consume 해 적재한다(Event-Carried State Transfer).
-- Phase 2 에서는 기존 read-model 과 dual-run(병렬). Phase 3 에서 조회를 이 테이블로 컷오버하고,
-- Phase 4 에서 settlement_db 로 물리 이전한다.
--
-- 현재는 단일 opslab 공유 DB 라 여기(order 마이그레이션)에 둔다 — 소유는 settlement.

CREATE TABLE IF NOT EXISTS opslab.settlement_payment_view (
    payment_id        BIGINT       PRIMARY KEY,           -- order payments.id
    order_id          BIGINT       NOT NULL,
    amount            NUMERIC(15, 2) NOT NULL,
    status            VARCHAR(20)  NOT NULL,              -- CAPTURED / REFUNDED ...
    captured_at       TIMESTAMP,
    seller_id         BIGINT,                             -- 미할당 시 NULL
    seller_tier       VARCHAR(20),
    settlement_cycle  VARCHAR(20),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 일/기간 정산 배치: 정산 대상일(captured_at) + 상태로 조회 (CapturedPaymentsAdapter 컷오버 대상)
CREATE INDEX IF NOT EXISTS idx_settlement_payment_view_captured
    ON opslab.settlement_payment_view (captured_at, status);

COMMENT ON TABLE opslab.settlement_payment_view IS
    'settlement 소유 결제 프로젝션 — PaymentCaptured 이벤트로 적재(ADR 0020 Phase 2). order payments 직접 매핑 대체.';
