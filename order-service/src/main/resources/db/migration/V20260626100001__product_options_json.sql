-- V20260626100001: 상품 원본 옵션 트리(options_json) JSONB 컬럼 추가
--
-- 상품 등록 시점의 옵션 구조를 임의 깊이(무한 뎁스) JSON 트리로 그대로 보관하기 위한 컬럼.
-- 진열/표시용 원천이며, 실제 재고 차감은 이 트리를 펼친 product_variants(SKU) 단위로 처리한다
-- (표현=JSON / 재고=SKU 책임 분리). null 이면 옵션 없는 상품.

ALTER TABLE opslab.products
    ADD COLUMN IF NOT EXISTS options_json JSONB;

COMMENT ON COLUMN opslab.products.options_json IS
    '상품 등록 시점의 원본 옵션 트리(임의 깊이 JSON). 재고는 product_variants 가 담당.';
