-- V20260616140000: settlement 소유 주문 프로젝션 (ADR 0020 Phase 3b, CQRS read model)
--
-- settlement 가 order 의 orders 테이블을 @Immutable 로 직접 매핑(SettlementOrderReadModel)하던 것을
-- 대체하기 위한 자체 프로젝션. OrderCreated 이벤트를 consume 해 적재한다.
-- QueryDSL 검색/리포트·ES 색인이 Phase 3b 컷오버에서 이 테이블을 조회한다.
-- 현재는 opslab 공유 DB(소유는 settlement), Phase 4 에서 settlement_db 로 이전.

CREATE TABLE IF NOT EXISTS opslab.settlement_order_view (
    order_id    BIGINT       PRIMARY KEY,                -- order orders.id
    user_id     BIGINT       NOT NULL,
    product_id  BIGINT,                                  -- 다건 주문 등에서 NULL 가능
    status      VARCHAR(40),
    amount      NUMERIC(15, 2),
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_settlement_order_view_user
    ON opslab.settlement_order_view (user_id);

COMMENT ON TABLE opslab.settlement_order_view IS
    'settlement 소유 주문 프로젝션 — OrderCreated 이벤트로 적재(ADR 0020 Phase 3b). order orders 직접 매핑 대체.';
