-- SKU 별 매입가(원가). 마진을 보려면 판 값 옆에 산 값이 있어야 한다.
--
-- 왜 SKU 단위인가.
--   같은 상품이라도 색·사이즈에 따라 사 오는 값이 다르다. 상품 하나에 원가 하나를 두면
--   나중에 쪼갤 수 없고, 쪼갤 수 없으면 "어느 옵션이 남는 장사인가"를 영영 못 본다.
--   추가금(additional_price)이 이미 SKU 마다 다른 것과 같은 이유다.
--
-- 왜 마진율 컬럼을 같이 두지 않는가.
--   마진율은 판매가에서 나오는 값인데, 이 몰의 판매가는 컬럼이 아니라 계산식이다
--   (기준가 + 추가금 - 정액할인 - 정률할인). 마진율을 굳혀 두면 저 넷 중 무엇이 바뀌어도
--   갱신되지 않고, 갱신되지 않은 채로도 조회는 성공한다 — 틀린 걸 아무도 모르는 형태의 값이다.
--   그래서 산 값만 남기고 마진은 읽을 때마다 계산한다.
--
-- NULL 의 뜻은 "0원에 샀다"가 아니라 "아직 모른다"이다. 기존 SKU 를 0 으로 채우면
--   마진 100% 짜리 상품이 무더기로 생겨 리포트가 통째로 거짓말을 한다. 그래서 기본값을 두지 않는다.

ALTER TABLE opslab.product_variants
    ADD COLUMN IF NOT EXISTS purchase_price NUMERIC(12, 2);

-- 음수 매입가는 입력 사고다. 역마진(매입가 > 판매가)은 있을 수 있고 그건 막지 않는다 —
-- 가려야 할 숫자가 아니라 봐야 할 숫자라서 계산 쪽에서도 0 으로 깎지 않는다.
ALTER TABLE opslab.product_variants
    DROP CONSTRAINT IF EXISTS chk_product_variants_purchase_price_non_negative;
ALTER TABLE opslab.product_variants
    ADD CONSTRAINT chk_product_variants_purchase_price_non_negative CHECK (
        purchase_price IS NULL OR purchase_price >= 0
    );

COMMENT ON COLUMN opslab.product_variants.purchase_price IS
    'SKU 1개를 사 오는 값(원). NULL 은 미입력이며 0원 매입이 아니다. 마진율은 저장하지 않고 판매가에서 계산한다.';
