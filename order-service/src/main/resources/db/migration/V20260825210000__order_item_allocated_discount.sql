-- V20260825210000: 쿠폰 할인의 라인 안분 — order_items.allocated_discount
--
-- [문제]
--   쿠폰 할인은 주문 단위 총액으로만 존재했다(orders.amount = 소계 - 할인 + 배송비). 라인은
--   여전히 정가(line_amount = unit_price × quantity)만 들고 있어서, 라인 단위 부분 취소가
--   "이 라인이 실제로 낸 돈"에 답할 수 없었고 정가를 그대로 환불했다.
--
--   50,000 × 2 = 소계 100,000 에 쿠폰 -20,000 → 결제 80,000 인 주문에서 실측한 값:
--     · 한 라인 취소     → 40,000 이어야 할 환불이 50,000 (1 만원 과환불)
--     · 두 라인 차례 취소 → 합계 80,000 이어야 할 환불이 100,000
--   두 번째가 실제 증상이다. PG 는 결제액을 넘는 환불을 거절하므로(RefundExceedsPayment)
--   돈이 더 나가는 게 아니라 <b>마지막 라인의 취소가 실패</b>한다 — 고객이 취소를 못 한다.
--
-- [조치]
--   결제 금액이 확정되는 자리(Order.createMultiItem)에서 할인을 라인에 안분해 여기 적어 둔다.
--   총액에서 역산하지 않는 이유: 라인별 몫은 총액에 남지 않고, 쿠폰은 나중에 수정·삭제되므로
--   환불 시점에 쿠폰을 다시 읽으면 그때의 쿠폰이 그때의 주문을 재해석하게 된다(주문서는 스냅샷이다).
--
--   배분은 line_amount 비례, 원 단위 내림 + 잔돈은 소수부가 큰 라인부터 1 원씩(largest remainder).
--   한 라인에 잔돈을 몰면 소액 라인이 여러 개인 주문에서 몫이 라인 금액을 넘어 순액이 음수가 된다.

ALTER TABLE opslab.order_items
    ADD COLUMN IF NOT EXISTS allocated_discount NUMERIC(19, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN opslab.order_items.allocated_discount IS
    '주문 전체 쿠폰 할인 중 이 라인이 짊어진 몫. 환불 단위 = line_amount - allocated_discount';

-- ── 기존 주문 백필 ────────────────────────────────────────────────────────────
-- 0 으로 두면 이미 쌓인 할인 주문들은 여전히 정가를 환불한다. 신규 주문만 고치는 것은 고치는 게
-- 아니다. 할인액은 주문에서 역산할 수 있다: discount = Σ line_amount - (amount - shipping_fee).
-- 도메인의 안분과 같은 규칙(비례 내림 + 잔돈 largest remainder)을 그대로 SQL 로 적용한다.
WITH order_discount AS (
    SELECT o.id                                                             AS order_id,
           SUM(i.line_amount)                                               AS subtotal,
           SUM(i.line_amount) - (o.amount - COALESCE(o.shipping_fee, 0))    AS discount
    FROM opslab.orders o
             JOIN opslab.order_items i ON i.order_id = o.id
    GROUP BY o.id, o.amount, o.shipping_fee
    HAVING SUM(i.line_amount) - (o.amount - COALESCE(o.shipping_fee, 0)) > 0
),
     ranked AS (
         SELECT i.id,
                FLOOR(d.discount * i.line_amount / d.subtotal)                    AS base,
                d.discount - SUM(FLOOR(d.discount * i.line_amount / d.subtotal))
                             OVER (PARTITION BY i.order_id)                       AS leftover,
                ROW_NUMBER() OVER (
                    PARTITION BY i.order_id
                    ORDER BY MOD(d.discount * i.line_amount, d.subtotal) DESC,
                             i.line_amount DESC,
                             i.id
                    )                                                             AS rn
         FROM opslab.order_items i
                  JOIN order_discount d ON d.order_id = i.order_id
     )
UPDATE opslab.order_items t
SET allocated_discount = r.base
    -- 내림으로 버린 잔돈을 소수부 큰 라인부터 1 원씩. 원 미만 나머지(정률 쿠폰)는 첫 라인이 받는다.
    + CASE WHEN r.rn <= FLOOR(r.leftover) THEN 1 ELSE 0 END
    + CASE WHEN r.rn = 1 THEN r.leftover - FLOOR(r.leftover) ELSE 0 END
FROM ranked r
WHERE t.id = r.id;

-- 백필 검증 — 조용히 어긋난 채로 배포되면 그 주문들은 계속 과환불한다. 어긋나면 배포를 세운다.
DO $$
DECLARE
    broken_sum   BIGINT;
    over_line    BIGINT;
BEGIN
    -- (1) 주문별 Σ 안분액 = 그 주문의 할인액.
    --     할인이 0 이하로 역산되는 주문은 대상이 아니다 — 배송비 컬럼이 생기기 전(V20260701100000)의
    --     레거시 주문은 amount 와 라인 합의 관계가 지금과 달라 역산이 성립하지 않는다. 그런 주문까지
    --     여기서 걸면 고칠 것이 없는 배포가 멈춘다.
    SELECT COUNT(*) INTO broken_sum
    FROM (SELECT o.id
          FROM opslab.orders o
                   JOIN opslab.order_items i ON i.order_id = o.id
          GROUP BY o.id, o.amount, o.shipping_fee
          HAVING SUM(i.line_amount) - (o.amount - COALESCE(o.shipping_fee, 0)) > 0
             AND SUM(i.allocated_discount)
                     <> SUM(i.line_amount) - (o.amount - COALESCE(o.shipping_fee, 0))) x;

    -- (2) 어떤 라인도 자기 금액보다 많이 할인받지 않는다(순액 음수 금지)
    SELECT COUNT(*) INTO over_line
    FROM opslab.order_items
    WHERE allocated_discount < 0 OR allocated_discount > line_amount;

    IF broken_sum > 0 OR over_line > 0 THEN
        RAISE EXCEPTION '할인 안분 백필 검증 실패: 합계 불일치 주문 %건, 라인 초과 %건',
            broken_sum, over_line;
    END IF;
END $$;
