-- V38: 장바구니(Cart) 도메인
--
-- 사용자별 1 개의 활성 장바구니. 항목은 cart_items 1:N. SKU(variantId) 또는 단일 상품 모두 지원.
-- TTL 정책: cleared_at 또는 last_active_at 기반으로 30일 정리 배치를 별도로 운영.
--
-- 본 구현은 RDB 기반이며, 향후 Redis 로 옮길 때 Port (LoadCartPort/SaveCartPort) 만 갈아끼우면 된다.

CREATE TABLE IF NOT EXISTS opslab.carts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL UNIQUE,         -- 사용자당 1 개의 활성 장바구니
    last_active_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_carts_last_active
    ON opslab.carts (last_active_at);

COMMENT ON TABLE opslab.carts IS
    '사용자별 활성 장바구니 (UNIQUE user_id). last_active_at 기반 30일 TTL 배치 정리.';

CREATE TABLE IF NOT EXISTS opslab.cart_items (
    id              BIGSERIAL PRIMARY KEY,
    cart_id         BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    variant_id      BIGINT,                                -- SKU 옵션 상품일 때만 채움
    quantity        INTEGER      NOT NULL,
    added_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cart_items_cart
        FOREIGN KEY (cart_id) REFERENCES opslab.carts(id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_items_quantity
        CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_cart_items_cart
    ON opslab.cart_items (cart_id);

-- 같은 장바구니 안에서 (productId, variantId) 조합은 1 개만 — 같은 상품 추가는 quantity 증가
CREATE UNIQUE INDEX IF NOT EXISTS uq_cart_items_cart_product_variant
    ON opslab.cart_items (cart_id, product_id, COALESCE(variant_id, -1));

COMMENT ON TABLE opslab.cart_items IS
    '장바구니 항목. (cart_id, product_id, variant_id) UNIQUE — 같은 SKU 추가는 수량 증가로 변환.';
