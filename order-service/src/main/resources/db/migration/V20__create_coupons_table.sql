-- 쿠폰 테이블
CREATE TABLE coupons (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(50) NOT NULL UNIQUE,
    type         VARCHAR(20) NOT NULL CHECK (type IN ('FIXED', 'PERCENTAGE')),
    discount_value NUMERIC(10, 2) NOT NULL CHECK (discount_value > 0),
    min_order_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    max_uses     INT NOT NULL DEFAULT 1,
    used_count   INT NOT NULL DEFAULT 0,
    expires_at   TIMESTAMP,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 쿠폰 사용 내역 테이블
CREATE TABLE coupon_usages (
    id         BIGSERIAL PRIMARY KEY,
    coupon_id  BIGINT NOT NULL REFERENCES coupons(id),
    user_id    BIGINT NOT NULL REFERENCES users(id),
    order_id   BIGINT REFERENCES orders(id),
    used_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_coupon_usage_user UNIQUE (coupon_id, user_id)
);

-- 시드 쿠폰 데이터
INSERT INTO coupons (code, type, discount_value, min_order_amount, max_uses, expires_at, is_active)
VALUES
    ('WELCOME10', 'PERCENTAGE', 10, 0, 100, NOW() + INTERVAL '1 year', TRUE),
    ('SAVE5000',  'FIXED',      5000, 30000, 50, NOW() + INTERVAL '6 months', TRUE);