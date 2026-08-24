-- 상품 이미지 테이블 생성 (PostgreSQL)
CREATE TABLE product_images (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    stored_file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    url VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT,
    height INT,
    checksum VARCHAR(64),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    order_index INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);

-- 인덱스 생성
CREATE INDEX idx_product_images_product_id ON product_images(product_id);
CREATE INDEX idx_product_images_product_primary ON product_images(product_id, is_primary) WHERE deleted_at IS NULL;
CREATE INDEX idx_product_images_product_order ON product_images(product_id, order_index) WHERE deleted_at IS NULL;
CREATE INDEX idx_product_images_deleted_at ON product_images(deleted_at);

-- product_images 테이블에 updated_at 트리거 적용
CREATE TRIGGER trigger_product_images_updated_at
    BEFORE UPDATE ON product_images
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 대표 이미지 unique 제약 (한 상품에 대표 이미지는 1개만)
-- PostgreSQL의 partial unique index 사용
CREATE UNIQUE INDEX idx_product_images_unique_primary
    ON product_images(product_id)
    WHERE is_primary = TRUE AND deleted_at IS NULL;
