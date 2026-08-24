package github.lms.lemuel.coupon.domain;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 쿠폰 할인 타입 — <b>enum 기반 Strategy 패턴</b>.
 *
 * <p>타입별 "할인액 계산"과 "할인값 제약 검증"을 각 enum 상수가 직접 구현한다. 덕분에
 * {@link Coupon} 은 {@code if (type == ...)} 분기 없이 {@code type.rawDiscount(...)} 로 위임만 하고,
 * 새 할인 타입(예: 등급별 정률, N+1 등)을 추가할 때 {@link Coupon} 코드 수정 없이 enum 상수만
 * 추가하면 된다(Open/Closed). 도메인 전반의 {@code SellerTier} enum-Strategy 와 동일한 관용.
 */
public enum CouponType {

    /** 정액 할인 (예: 5,000원). 주문 금액을 넘을 수 없다. */
    FIXED {
        @Override
        public BigDecimal rawDiscount(BigDecimal discountValue, BigDecimal orderAmount) {
            return discountValue.min(orderAmount);
        }

        @Override
        public void validateDiscountValue(BigDecimal discountValue) {
            // 정액은 별도 상한 없음 — 공통 검증(양수)만으로 충분.
        }
    },

    /** 정률 할인 (예: 10%). 원 단위 절사(FLOOR), 100% 초과 불가. */
    PERCENTAGE {
        @Override
        public BigDecimal rawDiscount(BigDecimal discountValue, BigDecimal orderAmount) {
            return orderAmount.multiply(discountValue).divide(HUNDRED, 0, RoundingMode.FLOOR);
        }

        @Override
        public void validateDiscountValue(BigDecimal discountValue) {
            if (discountValue.compareTo(HUNDRED) > 0) {
                throw new CouponInvariantViolationException("정률 할인은 100%를 초과할 수 없습니다.");
            }
        }
    };

    static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 상한({@code maxDiscountAmount}) 적용 <b>전</b>의 원 할인액을 타입별 규칙으로 계산한다.
     *
     * @param discountValue 쿠폰에 설정된 할인 값 (정액=금액, 정률=퍼센트)
     * @param orderAmount   주문 금액
     */
    public abstract BigDecimal rawDiscount(BigDecimal discountValue, BigDecimal orderAmount);

    /** 타입별 할인 값 제약 검증 (정률 100% 상한 등). 위반 시 {@link IllegalArgumentException}. */
    public abstract void validateDiscountValue(BigDecimal discountValue);
}
