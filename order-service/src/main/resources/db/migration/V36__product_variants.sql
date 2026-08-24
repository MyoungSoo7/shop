-- V36: 상품 옵션(SKU/Variant) 도메인
--
-- 색상·사이즈·용량 같은 옵션 조합을 별도 SKU 로 분리한다. 기존 products 테이블의
-- stock_quantity 는 "옵션 없는 상품" 의 단일 재고로 유지하고, 옵션 상품은 product_variants
-- 의 stock_quantity 가 진실의 원천이 된다.
--
-- Optimistic Lock(@Version) 컬럼으로 동시 재고 차감 시 race condition 을 방지한다.
-- 충돌 시 application 계층에서 N 회 재시도.

CREATE TABLE IF NOT EXISTS opslab.product_variants (
    id                BIGSERIAL PRIMARY KEY,
    product_id        BIGINT       NOT NULL,
    sku               VARCHAR(64)  NOT NULL UNIQUE,        -- 외부 노출용 변경 불가 식별자
    option_name       VARCHAR(200) NOT NULL,               -- 예: "색상:빨강/사이즈:L"
    additional_price  NUMERIC(10, 2) NOT NULL DEFAULT 0,   -- 옵션별 가산금 (음수도 가능)
    stock_quantity    INTEGER      NOT NULL DEFAULT 0,
    version           BIGINT       NOT NULL DEFAULT 0,     -- Optimistic Lock
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_variants_product
        FOREIGN KEY (product_id) REFERENCES opslab.products(id) ON DELETE CASCADE,
    CONSTRAINT chk_product_variants_status
        CHECK (status IN ('ACTIVE', 'OUT_OF_STOCK', 'DISCONTINUED')),
    CONSTRAINT chk_product_variants_stock
        CHECK (stock_quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_product_variants_product
    ON opslab.product_variants (product_id);

-- 같은 상품 안에서 같은 옵션 조합은 1 개만 존재
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_variants_product_option
    ON opslab.product_variants (product_id, option_name);

COMMENT ON TABLE opslab.product_variants IS
    '상품 옵션(SKU/Variant). 옵션 상품의 재고는 products.stock_quantity 가 아닌 여기서 관리.';
COMMENT ON COLUMN opslab.product_variants.version IS
    'JPA @Version Optimistic Lock 컬럼. 동시 재고 차감 시 충돌 감지 → 애플리케이션 재시도.';
COMMENT ON COLUMN opslab.product_variants.additional_price IS
    '옵션별 가산금. 최종 가격 = products.price + variants.additional_price';
