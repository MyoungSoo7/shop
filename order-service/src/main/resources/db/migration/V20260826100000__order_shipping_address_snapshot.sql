-- 주문 시점 배송지 스냅샷.
--
-- 지금까지 배송지는 shipments 테이블에만 있었고, 그마저도 주문 생성 경로에서는 채워지지 않았다
-- (고객 체크아웃에 배송지 입력이 없어 운영자가 사후에 손으로 만들어야 했다). 그 결과 두 가지가
-- 동시에 성립했다 — ① 결제까지 끝난 주문에 "어디로 보낼지"가 없다, ② shipments.address 는
-- PATCH /orders/{id}/shipment/address 로 덮어써지므로 "고객이 처음 어디로 요청했는가"가 남지 않는다.
--
-- 주문서는 영수증과 같은 성질의 기록이라 바뀌지 않아야 한다(order_items 가 상품명·단가를 굳혀 두는
-- 것과 같은 이유). 배송지 변경은 계속 shipments 가 담당하고, 여기 적힌 값은 주문 시점으로 고정된다.
--
-- 기존 주문은 스냅샷이 없으므로 전부 NULL 이다 — NOT NULL 을 걸지 않는 이유이며, 조회 코드는
-- NULL 을 "배송지를 받기 전에 만들어진 주문"으로 읽어야 한다.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS recipient_name  VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS recipient_phone VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS postal_code     VARCHAR(10);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS address1        VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS address2        VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_memo   VARCHAR(500);

COMMENT ON COLUMN orders.recipient_name IS
    '주문 시점 배송지 스냅샷 — 수령인. NULL 은 배송지 수집 이전에 생성된 주문. '
    '배송 중 변경은 shipments 에만 반영되고 이 값은 고정된다.';
