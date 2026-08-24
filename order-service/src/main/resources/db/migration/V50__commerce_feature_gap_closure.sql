-- V50: 권장 커머스 기능 보강
-- 회원 프로필/비활성화, 옵션 할인, 쿠폰 적용 대상, 주문 상태 변경 이력.

ALTER TABLE opslab.users
    ADD COLUMN IF NOT EXISTS name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS phone_number VARCHAR(30),
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_users_active
    ON opslab.users (is_active);

ALTER TABLE opslab.product_variants
    ADD COLUMN IF NOT EXISTS discount_price NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS discount_rate NUMERIC(5, 2);

ALTER TABLE opslab.product_variants
    ADD CONSTRAINT chk_product_variants_discount_price
        CHECK (discount_price IS NULL OR discount_price >= 0);

ALTER TABLE opslab.product_variants
    ADD CONSTRAINT chk_product_variants_discount_rate
        CHECK (discount_rate IS NULL OR (discount_rate > 0 AND discount_rate <= 100));

ALTER TABLE opslab.coupons
    ADD COLUMN IF NOT EXISTS target_type VARCHAR(20) NOT NULL DEFAULT 'ALL',
    ADD COLUMN IF NOT EXISTS target_id BIGINT,
    ADD COLUMN IF NOT EXISTS starts_at TIMESTAMP;

ALTER TABLE opslab.coupons
    ADD CONSTRAINT chk_coupons_target_type
        CHECK (target_type IN ('ALL', 'CATEGORY', 'PRODUCT'));

CREATE INDEX IF NOT EXISTS idx_coupons_target
    ON opslab.coupons (target_type, target_id);

CREATE TABLE IF NOT EXISTS opslab.order_status_history (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    previous_status VARCHAR(40),
    new_status      VARCHAR(40)  NOT NULL,
    changed_by      VARCHAR(255) NOT NULL,
    reason          VARCHAR(500),
    changed_at      TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_order_status_history_order
        FOREIGN KEY (order_id) REFERENCES opslab.orders(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_order_status_history_order
    ON opslab.order_status_history (order_id, changed_at DESC);
