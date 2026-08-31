-- TEXT 축(각인 문구처럼 구매자가 직접 적는 옵션)의 값을 담을 자리를 만든다.
--
-- 왜 SKU 가 아니라 주문 라인인가.
--   TEXT 축은 재고 단위를 만들지 않는다. "빨강/L/각인=민수" 를 따로 쌓아두는 창고는 없다.
--   그래서 product_variants 의 option_signature 에도 들어가지 않고(TEXT 축은 애초에
--   product_option_values 를 갖지 않는다), 남는 자리는 "이 주문 라인에서 뭐라고 적었나" 뿐이다.
--   같은 SKU 를 두 사람이 다른 문구로 사면 재고는 같은 칸에서 2개가 빠지고, 문구만 각자 남는다.
--
-- 왜 value_code/value_name 을 재활용하지 않는가.
--   그 두 칸은 카탈로그의 코드·이름을 그대로 베낀 자리다. 자유 입력을 여기 넣으면
--   "코드"가 사람이 친 문장이 되어 버려, 나중에 코드로 집계하거나 값 목록과 대조할 수 없다.
--   그래서 별도 칸을 두고, 두 형태 중 정확히 하나만 채워지도록 제약으로 못 박는다.

ALTER TABLE opslab.order_item_options
    ADD COLUMN IF NOT EXISTS text_value VARCHAR(200);

ALTER TABLE opslab.order_item_options
    ALTER COLUMN value_code DROP NOT NULL,
    ALTER COLUMN value_name DROP NOT NULL;

-- 선택형이면 (value_code, value_name) 둘 다, 자유입력이면 text_value 하나.
-- 셋 다 비거나 둘이 섞이는 줄은 주문서를 읽을 수 없게 만든다.
ALTER TABLE opslab.order_item_options
    DROP CONSTRAINT IF EXISTS chk_order_item_options_shape;
ALTER TABLE opslab.order_item_options
    ADD CONSTRAINT chk_order_item_options_shape CHECK (
        (value_code IS NOT NULL AND value_name IS NOT NULL AND text_value IS NULL)
     OR (value_code IS NULL AND value_name IS NULL AND text_value IS NOT NULL)
    );

COMMENT ON COLUMN opslab.order_item_options.text_value IS
    'TEXT 축에 구매자가 직접 적은 문구. 선택형 축에서는 NULL 이고, 대신 value_code/value_name 이 찬다.';

-- 축 쪽에는 "얼마나 길게 받을 것인가"를 둔다. 상품마다 각인 칸 길이가 다르다.
-- NULL 이면 200(컬럼 상한)을 쓴다 — 기존 축을 건드리지 않고 도입하기 위해서다.
ALTER TABLE opslab.product_option_axes
    ADD COLUMN IF NOT EXISTS text_max_length INTEGER;

ALTER TABLE opslab.product_option_axes
    DROP CONSTRAINT IF EXISTS chk_product_option_axes_text_len;
ALTER TABLE opslab.product_option_axes
    ADD CONSTRAINT chk_product_option_axes_text_len CHECK (
        text_max_length IS NULL OR (text_max_length BETWEEN 1 AND 200)
    );

COMMENT ON COLUMN opslab.product_option_axes.text_max_length IS
    'TEXT 축에서 받을 최대 글자 수(1~200). NULL 이면 200. 선택형 축에서는 의미 없다.';
