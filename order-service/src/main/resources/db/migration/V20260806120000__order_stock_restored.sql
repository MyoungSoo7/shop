-- 주문 재고 원복 완료 플래그.
--
-- 취소·환불·반품 회수 어느 경로로 들어와도 재고는 딱 한 번만 되돌아가야 한다. 플래그가 없으면
-- 관리자 환불 승인과 payment 의 REFUNDED 전이가 겹칠 때 같은 주문이 두 번 원복돼 없는 재고가 생긴다.
--
-- 기존 주문은 FALSE 로 채운다. 이미 종단에 도달한 과거 주문은 어차피 재원복 요청이 오지 않고
-- (종단 재전이는 상태머신이 차단), 반품 회수가 뒤늦게 들어오는 경우에만 1회 원복된다 —
-- 과거 정산·재고를 소급 변경하지 않는다.

ALTER TABLE opslab.orders
    ADD COLUMN IF NOT EXISTS stock_restored BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN opslab.orders.stock_restored IS
    '재고 원복 완료 여부 — 취소/환불/반품 회수 경로의 이중 원복을 막는 멱등 플래그';
