-- 회수 대기 재고 조회용 부분 인덱스.
--
-- 구동 쿼리: shipped = true AND stock_restored = false AND status IN ('REFUNDED','CANCELED')
--            ORDER BY updated_at ASC
--
-- 회수 대기는 전체 주문 중 소수(배송 후 환불에 한정)라 부분 인덱스가 크기·유지비 모두 유리하다.
-- 정렬 컬럼(updated_at)을 키에 두어 "가장 오래 묶인 건" 조회가 정렬 없이 끝난다.
-- 재고가 회수되면 stock_restored 가 true 로 바뀌며 인덱스에서 자동으로 빠진다.

CREATE INDEX IF NOT EXISTS idx_orders_awaiting_stock_reclaim
    ON opslab.orders (updated_at)
    WHERE shipped = TRUE
      AND stock_restored = FALSE
      AND status IN ('REFUNDED', 'CANCELED');

COMMENT ON INDEX opslab.idx_orders_awaiting_stock_reclaim IS
    '회수 대기 재고 조회 — 배송 후 환불·취소로 원복이 보류된 주문(오래된 순)';
