-- V37: 다건 주문(OrderItem) 도메인
--
-- 기존 orders 테이블의 product_id 컬럼은 단일 상품 주문의 호환성을 위해 유지하되 nullable 로 격하.
-- 신규 주문은 order_items 1:N 관계로 다건 SKU 를 담을 수 있다.
--
-- 가격 / 상품명은 주문 시점의 스냅샷으로 보관 — 추후 상품 가격이 바뀌어도
-- 이미 발생한 주문의 영수증·정산 금액은 변하지 않는다 (이력 보존).

CREATE TABLE IF NOT EXISTS opslab.order_items (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,                      -- 어떤 상품인지 (감사용)
    variant_id      BIGINT,                                     -- SKU 사용 시 채움
    sku             VARCHAR(64),                                -- 옵션 상품의 SKU 스냅샷
    product_name    VARCHAR(200) NOT NULL,                      -- 주문 시점 상품명 (이력 보존)
    unit_price      NUMERIC(12, 2) NOT NULL,                    -- 주문 시점 단가 (할인 적용 후)
    quantity        INTEGER      NOT NULL,
    line_amount     NUMERIC(12, 2) NOT NULL,                    -- unit_price * quantity (Generated 가능)
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES opslab.orders(id) ON DELETE CASCADE,
    CONSTRAINT chk_order_items_quantity
        CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price
        CHECK (unit_price >= 0),
    CONSTRAINT chk_order_items_line_amount
        CHECK (line_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_order_items_order
    ON opslab.order_items (order_id);

CREATE INDEX IF NOT EXISTS idx_order_items_product
    ON opslab.order_items (product_id);

CREATE INDEX IF NOT EXISTS idx_order_items_variant
    ON opslab.order_items (variant_id)
    WHERE variant_id IS NOT NULL;

COMMENT ON TABLE opslab.order_items IS
    '주문 라인 아이템 (1 주문 = N OrderItem). 상품명·단가는 주문 시점 스냅샷.';
COMMENT ON COLUMN opslab.order_items.unit_price IS
    '주문 시점 적용된 단가 (할인·쿠폰 반영 후). 추후 상품 가격 변경에도 영향 없음.';
COMMENT ON COLUMN opslab.order_items.line_amount IS
    'unit_price * quantity. Order.amount 는 모든 line_amount 의 합계.';

-- 기존 orders.product_id 컬럼을 nullable 로 격하 (다건 주문은 NULL, 단건 주문 호환은 채움)
ALTER TABLE opslab.orders ALTER COLUMN product_id DROP NOT NULL;

COMMENT ON COLUMN opslab.orders.product_id IS
    '레거시 단건 주문 호환용. 다건 주문은 NULL 이며 order_items 가 진실의 원천.';
