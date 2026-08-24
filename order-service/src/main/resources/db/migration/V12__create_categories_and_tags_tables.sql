-- 카테고리 테이블 생성 (PostgreSQL)
CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    parent_id BIGINT,
    display_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE SET NULL
);

-- 카테고리 인덱스
CREATE INDEX idx_categories_parent_id ON categories(parent_id);
CREATE INDEX idx_categories_is_active ON categories(is_active);
CREATE INDEX idx_categories_display_order ON categories(display_order);

-- 태그 테이블 생성 (PostgreSQL)
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    color VARCHAR(7) DEFAULT '#6B7280',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 태그 인덱스
CREATE INDEX idx_tags_name ON tags(name);

-- 상품-카테고리 연관 컬럼 추가 (Many-to-One: 상품은 하나의 카테고리에만 속함)
ALTER TABLE products ADD COLUMN IF NOT EXISTS category_id BIGINT;
ALTER TABLE products ADD CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL;
CREATE INDEX idx_products_category_id ON products(category_id);

-- 상품-태그 연관 테이블 (Many-to-Many: 상품은 여러 태그를 가질 수 있음)
CREATE TABLE product_tags (
    product_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id, tag_id),
    CONSTRAINT fk_product_tags_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_product_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);

-- 상품-태그 인덱스
CREATE INDEX idx_product_tags_tag_id ON product_tags(tag_id);

-- updated_at 자동 갱신 함수 (PostgreSQL)
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- categories 테이블에 updated_at 트리거 적용
CREATE TRIGGER trigger_categories_updated_at
    BEFORE UPDATE ON categories
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 기본 카테고리 데이터 삽입
INSERT INTO categories (name, description, display_order) VALUES
('전자제품', '전자제품 및 가전제품', 1),
('의류', '의류 및 패션 상품', 2),
('식품', '식품 및 음료', 3),
('도서', '도서 및 출판물', 4),
('기타', '기타 상품', 99);

-- 기본 태그 데이터 삽입
INSERT INTO tags (name, color) VALUES
('신상품', '#EF4444'),
('베스트', '#F59E0B'),
('할인', '#10B981'),
('추천', '#3B82F6'),
('한정판', '#8B5CF6');
