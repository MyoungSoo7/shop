-- 주문에 배송비(shipping_fee)와 배송 시작 여부(shipped) 컬럼 추가.
-- 환불 정책(RefundPolicy): 배송 시작 후 환불 시 배송비를 차감한다.
-- 기존 주문은 배송비 0, shipped=false 로 채워져 환불 동작이 이전과 동일(전액 환불)하게 유지된다.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_fee NUMERIC(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipped BOOLEAN NOT NULL DEFAULT FALSE;

-- orders.status 는 VARCHAR(20) 이었으나 CANCELLATION_REQUESTED(22자)/CANCELLATION_APPROVED(21자)를
-- 담지 못해 취소 상태가 실제로 저장될 수 없던 잠재 버그가 있었다. 여유롭게 30 으로 확장.
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(30);
