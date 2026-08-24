-- V20260716300000: order_items 참조무결성 FK 추가 (product_id / variant_id) — 크로스컷 DB 리뷰 F3 (order)
--
-- [설계 근거]
--   order_items 는 상품(products)과 옵션(product_variants)을 참조하지만 FK 가 없어(V37 은 order_id FK 만
--   보유) 상품·SKU 삭제 시 고아 라인이 남을 수 있었다. product_variants 는 이미 products(id) FK 를 가지므로
--   상품 계열 참조무결성의 마지막 구멍이 order_items 였다.
--   * product_id : 감사·정산 근거로 반드시 실재 상품을 가리켜야 함 → ON DELETE RESTRICT(참조 중이면 상품
--                  삭제 차단). NOT NULL 컬럼이라 SET NULL 은 불가하고, RESTRICT 가 도메인 의도(이력 보존)와 일치.
--   * variant_id: 옵션 상품에만 채워지는 nullable 참조. 라인의 SKU·상품명은 주문 시점 스냅샷으로 이미 보존
--                  되지만(V37), variant_id 자체는 감사 링크이므로 소실 없이 유지 → ON DELETE RESTRICT.
--   * NOT VALID → VALIDATE 2단계로 재검증 잠금 최소화. 시드는 order_items 에 행을 넣지 않아(V17/V50 은
--     products/product_variants 만 시드) 고아가 없고 VALIDATE 는 무해하게 통과한다.
--   * 스키마 표기는 V37/V36 과 동일하게 opslab. 접두(search_path=opslab 해석).

ALTER TABLE opslab.order_items
    ADD CONSTRAINT fk_order_items_product
        FOREIGN KEY (product_id) REFERENCES opslab.products (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE opslab.order_items VALIDATE CONSTRAINT fk_order_items_product;

ALTER TABLE opslab.order_items
    ADD CONSTRAINT fk_order_items_variant
        FOREIGN KEY (variant_id) REFERENCES opslab.product_variants (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE opslab.order_items VALIDATE CONSTRAINT fk_order_items_variant;

COMMENT ON CONSTRAINT fk_order_items_product ON opslab.order_items IS
    '주문 라인 → 상품 참조무결성. RESTRICT: 주문에 참조된 상품은 삭제 불가(정산·감사 근거 보존).';
COMMENT ON CONSTRAINT fk_order_items_variant ON opslab.order_items IS
    '주문 라인 → 옵션(SKU) 참조무결성(nullable). RESTRICT: 참조된 variant 삭제 차단(감사 링크 보존).';
