-- V20260813170000: 카테고리 단일화 (설계 Phase 5 — docs/inflearn/product-catalog-design.md — 로컬 전용(gitignore))
--
-- 상품이 카테고리에 붙는 경로가 두 개였다:
--   categories(V12)            + products.category_id            (1:N, 하드 삭제, name 글로벌 UNIQUE)
--   ecommerce_categories(V13)  + product_ecommerce_categories    (M:N, slug, depth<=2, soft delete)
-- 도메인도 두 벌이라 "이 상품의 카테고리" 를 묻는 곳마다 어느 쪽을 봐야 하는지가 달랐다.
--
-- 트리는 ecommerce_categories 로 단일화한다. 레거시 categories 는 행을 옮긴 뒤 통째로 없앤다.
--
-- 대표 카테고리는 products.category_id 대신 product_ecommerce_categories.is_primary 가 표현한다.
-- M:N 을 유지하면서 "주 분류 하나" 를 부분 유니크 인덱스로 강제하므로, 컬럼을 따로 두어
-- 매핑 테이블과 어긋날 여지를 남기지 않는다.

ALTER TABLE opslab.product_ecommerce_categories
    ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_primary_category
    ON opslab.product_ecommerce_categories (product_id) WHERE is_primary;

COMMENT ON COLUMN opslab.product_ecommerce_categories.is_primary IS
    '대표(주) 분류. 상품당 최대 1 행 — 폐기된 products.category_id 의 역할을 대신한다.';

-- 레거시 분류를 트리로 이관한다.
--   slug: 레거시 이름은 한글이라 ASCII slug 를 만들 수 없다. 결정적이고 충돌하지 않는
--         'legacy-<id>' 를 쓴다(사람이 나중에 정리할 수 있게 이름은 그대로 보존).
--   depth: 레거시 트리도 부모를 가질 수 있으나 실제 시드는 1 단계뿐이라, 부모가 있으면 1 로 둔다.
INSERT INTO opslab.ecommerce_categories (name, slug, parent_id, depth, sort_order, is_active,
                                         created_at, updated_at)
SELECT c.name,
       'legacy-' || c.id,
       NULL,
       CASE WHEN c.parent_id IS NULL THEN 0 ELSE 1 END,
       c.display_order,
       c.is_active,
       c.created_at,
       c.updated_at
FROM opslab.categories c
WHERE NOT EXISTS (
    SELECT 1 FROM opslab.ecommerce_categories e WHERE e.slug = 'legacy-' || c.id
);

-- 이관된 행의 부모를 다시 이어 붙인다(자식 → 부모 모두 위에서 만들어졌으므로 이제 매핑 가능).
UPDATE opslab.ecommerce_categories e
SET parent_id = p.id
FROM opslab.categories c
         JOIN opslab.ecommerce_categories p ON p.slug = 'legacy-' || c.parent_id
WHERE e.slug = 'legacy-' || c.id
  AND c.parent_id IS NOT NULL;

-- 상품의 대표 분류를 매핑 테이블로 옮긴다. 이미 매핑이 있으면 그 행을 대표로 승격한다.
INSERT INTO opslab.product_ecommerce_categories (product_id, category_id, is_primary, created_at)
SELECT p.id, e.id, TRUE, NOW()
FROM opslab.products p
         JOIN opslab.ecommerce_categories e ON e.slug = 'legacy-' || p.category_id
WHERE p.category_id IS NOT NULL
ON CONFLICT (product_id, category_id) DO UPDATE SET is_primary = TRUE;

-- 매핑은 있는데 대표가 없는 상품은 가장 먼저 붙은 분류를 대표로 삼는다(대표 부재 상태 제거).
UPDATE opslab.product_ecommerce_categories t
SET is_primary = TRUE
FROM (
    SELECT DISTINCT ON (product_id) product_id, category_id
    FROM opslab.product_ecommerce_categories
    ORDER BY product_id, created_at, category_id
) first_mapping
WHERE t.product_id = first_mapping.product_id
  AND t.category_id = first_mapping.category_id
  AND NOT EXISTS (
      SELECT 1 FROM opslab.product_ecommerce_categories x
      WHERE x.product_id = t.product_id AND x.is_primary
  );

ALTER TABLE opslab.products DROP CONSTRAINT IF EXISTS fk_products_category;
DROP INDEX IF EXISTS opslab.idx_products_category_id;
ALTER TABLE opslab.products DROP COLUMN IF EXISTS category_id;

DROP TRIGGER IF EXISTS trigger_categories_updated_at ON opslab.categories;
DROP TABLE IF EXISTS opslab.categories;
