-- V20260813180000: 카테고리 경로 비정규화 + 상품수 캐시 (설계 Phase 6 — docs/inflearn/product-catalog-design.md — 로컬 전용(gitignore))
--
-- 하위 트리 조회는 지금까지 매번 재귀(자기조인/CTE)였다. 경로를 행에 적어 두면
-- "이 카테고리 아래 전부" 가 WHERE path_ids @> ARRAY[:id] 한 줄이 된다.
--
-- ID 만 비정규화한다. 선행 사례는 경로에 이름까지 함께 박아(category1_name…category5_name)
-- 카테고리명을 바꿀 때마다 상품 전 행을 따라다니며 고쳐야 하는 부채를 만들었다.
-- 이름이 필요하면 조인한다 — 경로가 흔들리는 건 트리 구조가 바뀔 때뿐이다.
--
-- product_count 는 캐시다. 정본은 product_ecommerce_categories 의 실계수이며, 여기서 채우고
-- 이후에는 갱신 경로가 유지한다. 캐시와 정본의 불일치는 정합성 점검 항목이 된다.

ALTER TABLE opslab.ecommerce_categories
    ADD COLUMN IF NOT EXISTS path_ids      BIGINT[],
    ADD COLUMN IF NOT EXISTS path_slug     VARCHAR(900),
    ADD COLUMN IF NOT EXISTS product_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN opslab.ecommerce_categories.path_ids IS
    '루트→자기 경로의 id 배열. 하위 트리 조회는 path_ids @> ARRAY[:id] 한 줄로 끝난다.';
COMMENT ON COLUMN opslab.ecommerce_categories.path_slug IS
    '루트→자기 slug 경로(예: electronics/computers/laptops). 표시·링크용.';
COMMENT ON COLUMN opslab.ecommerce_categories.product_count IS
    '이 카테고리에 직접 매핑된 상품 수 캐시. 정본은 product_ecommerce_categories 실계수.';

-- 기존 행 경로 채우기 — 루트에서 내려오며 부모 경로에 자기를 덧붙인다.
WITH RECURSIVE tree AS (
    SELECT id, slug, parent_id,
           ARRAY[id]::BIGINT[] AS path_ids,
           slug::TEXT          AS path_slug
    FROM opslab.ecommerce_categories
    WHERE parent_id IS NULL
    UNION ALL
    SELECT c.id, c.slug, c.parent_id,
           t.path_ids || c.id,
           t.path_slug || '/' || c.slug
    FROM opslab.ecommerce_categories c
             JOIN tree t ON c.parent_id = t.id
)
UPDATE opslab.ecommerce_categories e
SET path_ids  = t.path_ids,
    path_slug = t.path_slug
FROM tree t
WHERE e.id = t.id;

-- 부모가 끊긴 행(고아)도 최소한 자기 자신은 경로로 갖게 한다 — NULL 경로는 조회에서 조용히 빠진다.
UPDATE opslab.ecommerce_categories
SET path_ids  = ARRAY[id]::BIGINT[],
    path_slug = slug
WHERE path_ids IS NULL;

UPDATE opslab.ecommerce_categories e
SET product_count = COALESCE(c.cnt, 0)
FROM (SELECT category_id, COUNT(*) AS cnt
      FROM opslab.product_ecommerce_categories
      GROUP BY category_id) c
WHERE e.id = c.category_id;

CREATE INDEX IF NOT EXISTS idx_ecommerce_categories_path_ids
    ON opslab.ecommerce_categories USING GIN (path_ids);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ecommerce_categories_path_slug
    ON opslab.ecommerce_categories (path_slug) WHERE deleted_at IS NULL;
