-- 주문 취소·환불 시 쿠폰을 되돌려 준다.
--
-- 레거시 커머스(ssgb2e-front `OrderServiceImpl.orderCancelProcess`)는 취소 라인의 쿠폰 정보를 읽어
-- `updateCouponStatus(couponsts='1')` 로 쿠폰 행 자체를 "미사용"으로 되돌렸다. 아이디어는 옳지만
-- 사용 이력이 그 자리에서 사라져 "언제 썼다가 언제 돌려받았는지"가 남지 않는다.
--
-- 여기서는 사용 이력을 지우지 않고 revoked_at 으로 무효화한다(원장 보존형). 대신 1인 1매 제약을
-- 전체 UNIQUE 에서 "살아 있는 사용에만 걸리는" 부분 UNIQUE 인덱스로 바꿔야 재사용이 열린다 —
-- 전체 UNIQUE 를 그대로 두면 돌려받은 쿠폰을 다시 쓰려는 순간 제약 위반으로 막힌다.

ALTER TABLE coupon_usages ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;
ALTER TABLE coupon_usages ADD COLUMN IF NOT EXISTS revoke_reason VARCHAR(200);

ALTER TABLE coupon_usages DROP CONSTRAINT IF EXISTS uq_coupon_usage_user;

CREATE UNIQUE INDEX IF NOT EXISTS uq_coupon_usage_user_active
    ON coupon_usages (coupon_id, user_id)
    WHERE revoked_at IS NULL;

-- 취소 시 "이 주문이 쓴 쿠폰"을 찾는 경로. 무효화된 행은 다시 회수할 일이 없으므로 부분 인덱스로 둔다.
CREATE INDEX IF NOT EXISTS idx_coupon_usages_order_active
    ON coupon_usages (order_id)
    WHERE revoked_at IS NULL;

COMMENT ON COLUMN coupon_usages.revoked_at IS '쿠폰 사용 무효화 시각(주문 취소·환불). NULL 이면 유효한 사용';
COMMENT ON COLUMN coupon_usages.revoke_reason IS '무효화 사유(주문 취소/환불 등)';
