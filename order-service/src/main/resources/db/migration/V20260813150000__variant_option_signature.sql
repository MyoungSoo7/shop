-- V20260813150000: SKU 조합 ↔ 옵션 값 매핑과 조합 서명 (설계 Phase 2 — docs/inflearn/product-catalog-design.md — 로컬 전용(gitignore))
--
-- Phase 1 이 축·값을 테이블로 만들었지만, 어떤 SKU 가 어떤 값 조합인지는 여전히
-- product_variants.option_name 문자열 안에만 있었다. 이 마이그레이션이 그 연결을 실체로 만든다.
--
--   product_variant_option_values : SKU ↔ (상품 축, 상품 값) 매핑. 파셋 검색의 조인키.
--   product_variants.option_signature : 선택 조합의 정규화 해시. 정확 조회의 조인키.
--
-- 서명이 필요한 이유: 매핑만으로 "이 조합의 SKU" 를 찾으려면 GROUP BY … HAVING count = N 이 필요하다.
-- 서명은 그걸 (product_id, option_signature) 유니크 인덱스 단건 조회로 바꾼다.
-- 계산 규칙(축 id 오름차순 정렬 → "axisId:valueId" 를 '|' 로 연결 → SHA-256)은 도메인이 소유한다 —
-- SQL 로도 계산하면 정렬 규칙이 두 벌이 되어 option_name 문자열 규약과 같은 함정이 재현된다.
--
-- 순수 추가다. option_signature 는 nullable 로 들어오고(백필 전 SKU 는 NULL), 기존 조회 경로인
-- UNIQUE(product_id, option_name) 은 Phase 4 까지 그대로 살려 둔다.

CREATE TABLE IF NOT EXISTS opslab.product_variant_option_values (
    variant_id              BIGINT NOT NULL,
    product_option_axis_id  BIGINT NOT NULL,
    product_option_value_id BIGINT NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),

    -- 축당 값 1 개: 한 SKU 가 같은 축에 두 값을 가질 수 없다.
    PRIMARY KEY (variant_id, product_option_axis_id),

    CONSTRAINT fk_pvov_variant
        FOREIGN KEY (variant_id) REFERENCES opslab.product_variants (id) ON DELETE CASCADE,
    CONSTRAINT fk_pvov_axis
        FOREIGN KEY (product_option_axis_id) REFERENCES opslab.product_option_axes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pvov_value
        FOREIGN KEY (product_option_value_id) REFERENCES opslab.product_option_values (id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_pvov_value
    ON opslab.product_variant_option_values (product_option_value_id);

COMMENT ON TABLE opslab.product_variant_option_values IS
    'SKU 조합 ↔ 상품 옵션 값 매핑. "색상=빨강인 판매중 SKU" 같은 파셋 조회가 조인 한 번이 된다.';

ALTER TABLE opslab.product_variants
    ADD COLUMN IF NOT EXISTS option_signature VARCHAR(64);

-- NULL 은 유니크 판정에서 서로 충돌하지 않으므로 백필 전 SKU 가 이 인덱스를 막지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_variants_signature
    ON opslab.product_variants (product_id, option_signature);

COMMENT ON COLUMN opslab.product_variants.option_signature IS
    '선택 조합의 SHA-256 서명(축 id 오름차순 "axisId:valueId" 를 | 로 연결). '
    'option_name 문자열을 조인키 자리에서 밀어낸다 — NULL 은 아직 백필되지 않은 SKU.';
