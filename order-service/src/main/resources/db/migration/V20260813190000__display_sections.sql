-- V20260813190000: 진열/기획전 편성 (설계 Phase 7 — docs/inflearn/product-catalog-design.md — 로컬 전용(gitignore))
--
-- 분류(카테고리)와 진열은 다른 축이다. 분류는 상품의 성질이고, 진열은 기간과 대상이 있는 편성이다.
-- 선행 사례들은 기획전을 분류 트리와 같은 모양으로 복제했고(별도 기획전 카테고리 테이블 / 노출 플래그를
-- 같은 행에 4 컬럼으로 섞기), 그 결과 트리 로직이 두 벌이 되거나 카테고리 행이 진열 속성으로 오염됐다.
--
-- 여기서는 기획전을 트리가 아니라 편성(section)으로 모델링한다. 메인 진열·기획전·카테고리 베스트를
-- kind 하나로 구분해 한 테이블에서 다루므로 트리 코드가 복제되지 않는다.

CREATE TABLE IF NOT EXISTS opslab.display_sections (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(30)  NOT NULL,
    category_id BIGINT,
    starts_at   TIMESTAMP,
    ends_at     TIMESTAMP,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_display_sections_category
        FOREIGN KEY (category_id) REFERENCES opslab.ecommerce_categories (id) ON DELETE SET NULL,
    CONSTRAINT chk_display_sections_kind
        CHECK (kind IN ('MAIN', 'EXHIBITION', 'CATEGORY_BEST')),
    CONSTRAINT chk_display_sections_period
        CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT chk_display_sections_sort
        CHECK (sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_display_sections_active
    ON opslab.display_sections (is_active, sort_order);

COMMENT ON TABLE opslab.display_sections IS
    '진열 편성. 분류(ecommerce_categories)와 다른 축이다 — 기간·정렬을 갖고 트리를 복제하지 않는다.';
COMMENT ON COLUMN opslab.display_sections.category_id IS
    'CATEGORY_BEST 일 때만 의미가 있다. 카테고리가 사라져도 편성은 남기려고 ON DELETE SET NULL.';

CREATE TABLE IF NOT EXISTS opslab.display_section_items (
    section_id BIGINT  NOT NULL,
    product_id BIGINT  NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_pinned  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    PRIMARY KEY (section_id, product_id),
    CONSTRAINT fk_display_items_section
        FOREIGN KEY (section_id) REFERENCES opslab.display_sections (id) ON DELETE CASCADE,
    CONSTRAINT fk_display_items_product
        FOREIGN KEY (product_id) REFERENCES opslab.products (id) ON DELETE CASCADE,
    CONSTRAINT chk_display_items_sort
        CHECK (sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_display_items_section_sort
    ON opslab.display_section_items (section_id, is_pinned DESC, sort_order);

COMMENT ON TABLE opslab.display_section_items IS
    '편성에 담긴 상품. 고정(is_pinned)이 정렬보다 우선한다 — 운영자가 맨 앞에 못 박는 자리.';
