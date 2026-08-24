-- V20260813140000: 옵션 축/값 카탈로그 (설계 Phase 1 — docs/inflearn/product-catalog-design.md — 로컬 전용(gitignore))
--
-- 지금까지 상품 옵션은 표현이 products.options_json(JSONB), 재고가 product_variants(SKU) 였고
-- 둘을 잇는 건 product_variants.option_name 의 "색상:빨강/사이즈:L" 문자열 규약뿐이었다.
-- 축(색상/사이즈)과 값(빨강/L)이 테이블에 없어 파셋 검색·표준화·안전한 이름 변경이 불가능했다.
--
-- 이 마이그레이션은 4개 테이블로 그 층을 만든다(순수 추가 — 읽는 코드가 아직 없으므로 무중단):
--   option_axes            표준 축 카탈로그   (COLOR, SIZE …)   — 전 상품 공유
--   option_axis_values     축의 표준 값       (RED, L …)
--   product_option_axes    상품이 채택한 축   (차수 = sort_order, 필수 여부)
--   product_option_values  상품이 노출하는 값 (표준값 중 이 상품이 파는 것만)
--
-- SKU 조합과의 연결(product_variant_option_values, option_signature)은 Phase 2 에서 붙인다.

CREATE TABLE IF NOT EXISTS opslab.option_axes (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    input_type  VARCHAR(20)  NOT NULL DEFAULT 'SELECT',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_option_axes_input_type
        CHECK (input_type IN ('SELECT', 'SWATCH', 'TEXT'))
);

COMMENT ON TABLE opslab.option_axes IS
    '표준 옵션 축 카탈로그(색상·사이즈 등). 상품 간 재사용되며 파셋 검색의 축이 된다.';
COMMENT ON COLUMN opslab.option_axes.code IS
    '기계 식별자. 공백·":"·"/" 를 포함하지 않는다(표시 규약 option_name 과의 충돌 방지).';

CREATE TABLE IF NOT EXISTS opslab.option_axis_values (
    id          BIGSERIAL PRIMARY KEY,
    axis_id     BIGINT       NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    swatch_hex  VARCHAR(7),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_option_axis_values_axis
        FOREIGN KEY (axis_id) REFERENCES opslab.option_axes (id) ON DELETE CASCADE,
    CONSTRAINT uq_option_axis_values_code
        UNIQUE (axis_id, code),
    CONSTRAINT chk_option_axis_values_swatch
        CHECK (swatch_hex IS NULL OR swatch_hex ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX IF NOT EXISTS idx_option_axis_values_axis
    ON opslab.option_axis_values (axis_id, sort_order);

COMMENT ON TABLE opslab.option_axis_values IS
    '옵션 축의 표준 값. 값 이름의 단일 진실원 — SKU·매핑은 ID 로 묶여 이름 변경에 영향받지 않는다.';

CREATE TABLE IF NOT EXISTS opslab.product_option_axes (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT  NOT NULL,
    axis_id     BIGINT  NOT NULL,
    sort_order  INTEGER NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_option_axes_product
        FOREIGN KEY (product_id) REFERENCES opslab.products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_option_axes_axis
        FOREIGN KEY (axis_id) REFERENCES opslab.option_axes (id) ON DELETE RESTRICT,
    CONSTRAINT uq_product_option_axes
        UNIQUE (product_id, axis_id),
    CONSTRAINT uq_product_option_axes_order
        UNIQUE (product_id, sort_order),
    CONSTRAINT chk_product_option_axes_order
        CHECK (sort_order >= 0)
);

COMMENT ON TABLE opslab.product_option_axes IS
    '상품이 채택한 옵션 축. sort_order 가 곧 차수(0=1차)이며 상한이 없다 — 2단 고정 구조를 쓰지 않는다.';

CREATE TABLE IF NOT EXISTS opslab.product_option_values (
    id                     BIGSERIAL PRIMARY KEY,
    product_option_axis_id BIGINT  NOT NULL,
    axis_value_id          BIGINT  NOT NULL,
    sort_order             INTEGER NOT NULL DEFAULT 0,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_option_values_axis
        FOREIGN KEY (product_option_axis_id) REFERENCES opslab.product_option_axes (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_option_values_value
        FOREIGN KEY (axis_value_id) REFERENCES opslab.option_axis_values (id) ON DELETE RESTRICT,
    CONSTRAINT uq_product_option_values
        UNIQUE (product_option_axis_id, axis_value_id),
    CONSTRAINT chk_product_option_values_order
        CHECK (sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_product_option_values_value
    ON opslab.product_option_values (axis_value_id);

COMMENT ON TABLE opslab.product_option_values IS
    '상품이 실제로 노출하는 옵션 값. 표준값 전체가 아니라 이 상품이 파는 부분집합.';
