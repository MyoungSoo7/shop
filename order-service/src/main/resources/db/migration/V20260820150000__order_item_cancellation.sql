-- V20260820150000: 라인 단위 부분 취소 — order_items.canceled_at
--
-- [문제]
--   주문 취소는 주문 전체(status → CANCELED)뿐이었고 환불은 금액 단위였다. 그래서 "3 개 중 1 개만
--   취소"를 표현할 자리가 없었고, 부분 환불이 일어나도 어떤 상품이 빠졌는지 주문서가 알지 못했다.
--   그 결과 배송비 재산정(무료배송 임계를 채우던 상품이 빠지면 면제됐던 배송비가 되살아난다)이
--   불가능했다 — 남은 라인을 알 수 없으니 다시 계산할 입력이 없다.
--
-- [조치]
--   라인에 취소 시각을 둔다. 주문 총액(orders.amount)은 발행된 영수증이라 건드리지 않는다 —
--   실제로 얼마를 되돌려줬는지는 payments.refunded_amount 가 이미 들고 있고, 여기서는
--   "어떤 라인이 살아 있는가"만 기록한다. 두 장부가 각자의 사실을 갖고 서로를 덮어쓰지 않는다.
--
--   NULL = 살아 있는 라인. 기존 행은 전부 NULL 이 되므로 과거 주문의 해석이 바뀌지 않는다.

ALTER TABLE opslab.order_items
    ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMP;

COMMENT ON COLUMN opslab.order_items.canceled_at IS
    '라인 부분 취소 시각. NULL 이면 살아 있는 라인(배송비 재산정·출고 대상)';

-- 살아 있는 라인 조회가 주 경로(배송비 재산정·출고 목록) — 취소분은 소수라 부분 인덱스가 유리하다.
CREATE INDEX IF NOT EXISTS idx_order_items_active
    ON opslab.order_items (order_id)
    WHERE canceled_at IS NULL;
