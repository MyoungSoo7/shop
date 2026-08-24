-- V20260813160000: 주문 옵션 스냅샷 + option_name 유니크 해제 (설계 Phase 4 — docs/inflearn/product-catalog-design.md — 로컬 전용(gitignore))
--
-- (1) UNIQUE(product_id, option_name) 해제
--     이 유니크는 "색상:빨강/사이즈:L" 문자열을 조합 유일성의 기준으로 못박고 있었다. 조합 유일성은
--     이제 uq_product_variants_signature(product_id, option_signature)가 축·값 id 로 보장한다.
--     option_name 은 사람이 읽는 라벨로 남으므로 조회용 일반 인덱스만 유지한다(이관 중 레거시 폴백 경로).
--
-- (2) order_item_options — 주문 라인의 옵션 스냅샷
--     옵션이 축/값 테이블로 쪼개진 뒤로 "주문 당시 무엇을 골랐는지" 는 조인 네 번을 타야 복원되고,
--     값이 비활성화되거나 이름이 바뀌면 복원이 흐려진다. 주문서는 몇 년 뒤에도 그대로 읽혀야 하므로
--     축·값의 코드와 이름을 그 시점 그대로 적어 둔다.
--
--     금액은 스냅샷하지 않는다. 라인 단가는 order_items.unit_price 가 이미 보존하고, 축별 가산금을
--     여기 또 적으면 합계가 두 곳에서 갈린다(환불 역산의 정본은 ProductVariant.effectiveUnitPrice 순서다).

DROP INDEX IF EXISTS opslab.uq_product_variants_product_option;

ALTER TABLE opslab.product_variants
    DROP CONSTRAINT IF EXISTS uq_product_variants_product_option;

CREATE INDEX IF NOT EXISTS idx_product_variants_product_option
    ON opslab.product_variants (product_id, option_name);

COMMENT ON COLUMN opslab.product_variants.option_name IS
    '사람이 읽는 옵션 라벨. 조합 유일성의 기준이 아니다 — 그 역할은 option_signature 가 맡는다.';

CREATE TABLE IF NOT EXISTS opslab.order_item_options (
    id               BIGSERIAL PRIMARY KEY,
    order_item_id    BIGINT       NOT NULL,
    axis_sort_order  INTEGER      NOT NULL,
    axis_code        VARCHAR(50)  NOT NULL,
    axis_name        VARCHAR(100) NOT NULL,
    value_code       VARCHAR(50)  NOT NULL,
    value_name       VARCHAR(100) NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_order_item_options_item
        FOREIGN KEY (order_item_id) REFERENCES opslab.order_items (id) ON DELETE CASCADE,
    CONSTRAINT uq_order_item_options
        UNIQUE (order_item_id, axis_sort_order),
    CONSTRAINT chk_order_item_options_order
        CHECK (axis_sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_order_item_options_item
    ON opslab.order_item_options (order_item_id);

COMMENT ON TABLE opslab.order_item_options IS
    '주문 라인의 옵션 선택 스냅샷. 옵션 값이 비활성화·개명돼도 주문서는 그대로 읽힌다. 금액은 담지 않는다.';
