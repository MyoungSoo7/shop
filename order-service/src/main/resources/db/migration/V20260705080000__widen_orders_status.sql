-- orders.status 가 VARCHAR(20) 이라 22자 enum(CANCELLATION_REQUESTED),
-- 21자 enum(CANCELLATION_APPROVED) 저장 시 "value too long" 으로 취소 신청/승인이 500.
-- order_status_history.previous_status/new_status 는 이미 VARCHAR(40) — orders 만 누락돼 있던 것을 정합.
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(40);
