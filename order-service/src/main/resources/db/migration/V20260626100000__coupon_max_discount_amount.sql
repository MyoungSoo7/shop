-- V20260626100000: 쿠폰 정률 할인 상한(max_discount_amount) 컬럼 추가
--
-- 도메인 Coupon.calculateDiscount() 는 maxDiscountAmount 로 정률(PERCENTAGE) 쿠폰의
-- 할인 상한을 캡해 왔으나, coupons 테이블/엔티티에 컬럼이 없어 저장 시 값이 유실되고
-- 재조회 시 null(=무제한) 로 복원되어 상한이 영구 미적용되는 버그가 있었다.
-- 컬럼을 추가해 생성 시점의 상한이 영속·복원되도록 한다. null 이면 기존과 동일하게 무제한.

ALTER TABLE opslab.coupons
    ADD COLUMN IF NOT EXISTS max_discount_amount NUMERIC(10, 2);

ALTER TABLE opslab.coupons
    ADD CONSTRAINT chk_coupons_max_discount_amount
        CHECK (max_discount_amount IS NULL OR max_discount_amount > 0);
