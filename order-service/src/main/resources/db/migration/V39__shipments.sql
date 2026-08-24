-- V39: 배송(Shipping) 도메인
--
-- 주문 1 건당 배송 1 건 (1:1). 멀티 셀러 분할 배송은 향후 v2 에서 1:N 으로 확장.
-- 배송 상태머신: PENDING → READY → SHIPPED → IN_TRANSIT → DELIVERED → (선택) RETURNED.

CREATE TABLE IF NOT EXISTS opslab.shipments (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT       NOT NULL UNIQUE,
    recipient_name    VARCHAR(100) NOT NULL,
    phone             VARCHAR(30)  NOT NULL,
    postal_code       VARCHAR(10)  NOT NULL,
    address1          VARCHAR(200) NOT NULL,
    address2          VARCHAR(200),
    delivery_memo     VARCHAR(500),
    carrier           VARCHAR(50),                          -- 택배사 (CJ대한통운, 한진, 우체국 등)
    tracking_number   VARCHAR(100),                         -- 운송장 번호
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    shipped_at        TIMESTAMP,
    delivered_at      TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_shipments_order
        FOREIGN KEY (order_id) REFERENCES opslab.orders(id) ON DELETE CASCADE,
    CONSTRAINT chk_shipments_status
        CHECK (status IN ('PENDING', 'READY', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED'))
);

CREATE INDEX IF NOT EXISTS idx_shipments_status
    ON opslab.shipments (status);

-- 운송장 번호로 검색하는 외부 콜백/조회 API 용
CREATE INDEX IF NOT EXISTS idx_shipments_tracking
    ON opslab.shipments (carrier, tracking_number)
    WHERE tracking_number IS NOT NULL;

COMMENT ON COLUMN opslab.shipments.status IS
    'PENDING=주문생성됨/주소확정 대기, READY=출고대기, SHIPPED=출고완료(운송장 발급), '
    'IN_TRANSIT=배송중, DELIVERED=배송완료, RETURNED=반품처리';
