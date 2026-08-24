-- 이커머스 카테고리 테이블 생성 (PostgreSQL: 트리 구조, soft delete)
CREATE TABLE ecommerce_categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(300) NOT NULL UNIQUE,
    parent_id BIGINT,
    depth INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_ecommerce_category_parent FOREIGN KEY (parent_id) REFERENCES ecommerce_categories(id),
    CONSTRAINT chk_ecommerce_category_depth CHECK (depth >= 0 AND depth <= 2)
);

-- 이커머스 카테고리 인덱스
CREATE INDEX idx_ecommerce_categories_slug ON ecommerce_categories(slug);
CREATE INDEX idx_ecommerce_categories_parent_id ON ecommerce_categories(parent_id);
CREATE INDEX idx_ecommerce_categories_is_active ON ecommerce_categories(is_active);
CREATE INDEX idx_ecommerce_categories_deleted_at ON ecommerce_categories(deleted_at);
CREATE INDEX idx_ecommerce_categories_sort_order ON ecommerce_categories(sort_order);

-- 상품-이커머스카테고리 매핑 테이블 (다대다)
CREATE TABLE product_ecommerce_categories (
    product_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id, category_id),
    CONSTRAINT fk_product_ecommerce_cat_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_product_ecommerce_cat_category FOREIGN KEY (category_id) REFERENCES ecommerce_categories(id) ON DELETE RESTRICT
);

-- 상품-이커머스카테고리 인덱스
CREATE INDEX idx_product_ecommerce_categories_category_id ON product_ecommerce_categories(category_id);

-- ecommerce_categories 테이블에 updated_at 트리거 적용
CREATE TRIGGER trigger_ecommerce_categories_updated_at
    BEFORE UPDATE ON ecommerce_categories
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 샘플 데이터: 1단계 카테고리
INSERT INTO ecommerce_categories (name, slug, parent_id, depth, sort_order) VALUES
('전자제품', 'electronics', NULL, 0, 1),
('의류', 'clothing', NULL, 0, 2),
('식품', 'food', NULL, 0, 3);

-- 샘플 데이터: 2단계 카테고리
INSERT INTO ecommerce_categories (name, slug, parent_id, depth, sort_order) VALUES
('컴퓨터', 'electronics-computers', 1, 1, 1),
('스마트폰', 'electronics-smartphones', 1, 1, 2),
('남성의류', 'clothing-men', 2, 1, 1),
('여성의류', 'clothing-women', 2, 1, 2);

-- 샘플 데이터: 3단계 카테고리
INSERT INTO ecommerce_categories (name, slug, parent_id, depth, sort_order) VALUES
('노트북', 'electronics-computers-laptops', 4, 2, 1),
('데스크탑', 'electronics-computers-desktops', 4, 2, 2),
('남성셔츠', 'clothing-men-shirts', 6, 2, 1);
