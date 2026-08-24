-- V10: Create products table
CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_price_non_negative CHECK (price >= 0),
    CONSTRAINT chk_stock_non_negative CHECK (stock_quantity >= 0),
    CONSTRAINT chk_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE', 'OUT_OF_STOCK', 'DISCONTINUED'))
);

-- Create indexes for better query performance
CREATE INDEX idx_products_name ON products(name);
CREATE INDEX idx_products_status ON products(status);
CREATE INDEX idx_products_status_stock ON products(status, stock_quantity);

-- Add comment
COMMENT ON TABLE products IS '상품 정보 테이블';
COMMENT ON COLUMN products.id IS '상품 ID';
COMMENT ON COLUMN products.name IS '상품명';
COMMENT ON COLUMN products.description IS '상품 설명';
COMMENT ON COLUMN products.price IS '상품 가격';
COMMENT ON COLUMN products.stock_quantity IS '재고 수량';
COMMENT ON COLUMN products.status IS '상품 상태 (ACTIVE, INACTIVE, OUT_OF_STOCK, DISCONTINUED)';
COMMENT ON COLUMN products.created_at IS '생성 일시';
COMMENT ON COLUMN products.updated_at IS '수정 일시';
