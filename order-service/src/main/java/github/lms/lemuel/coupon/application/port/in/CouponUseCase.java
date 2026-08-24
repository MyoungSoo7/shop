package github.lms.lemuel.coupon.application.port.in;

import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface CouponUseCase {

    Coupon createCoupon(CreateCouponCommand command);

    /**
     * 쿠폰 검증: 코드, 사용자 중복 사용 여부, 주문 금액 조건 확인
     * 유효하면 할인 금액을 포함한 Coupon 반환
     */
    ValidateResult validateCoupon(String code, Long userId, BigDecimal orderAmount);

    /**
     * 쿠폰 사용 처리: 사용 횟수 증가 + 사용 내역 기록
     */
    void useCoupon(String code, Long userId, Long orderId);

    /**
     * 주문 취소·환불로 쿠폰을 되돌려 준다: 사용 이력 무효화 + 사용 횟수 감소.
     *
     * <p>돌려주지 않으면 "결제는 환불받았는데 쿠폰만 소멸"하는 구멍이 남는다 — 1회용 쿠폰에서는
     * 고객이 할인 자체를 잃는다. 여러 종단 경로(취소 승인 · 환불 승인 · PG 환불 콜백)가 겹쳐
     * 호출해도 안전하도록 <b>멱등</b>하다.
     *
     * @return 이번 호출로 되돌린 쿠폰 수. 되돌릴 것이 없었으면 0
     */
    int restoreCouponsForOrder(Long orderId, String reason);

    List<Coupon> getAllCoupons();

    List<ValidateResult> getAvailableCoupons(Long userId, BigDecimal orderAmount, Long productId, Long categoryId);

    record CreateCouponCommand(
            String code,
            CouponType type,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscountAmount,
            int maxUses,
            String targetType,
            Long targetId,
            LocalDateTime startsAt,
            LocalDateTime expiresAt
    ) {
        public CreateCouponCommand(String code, CouponType type, BigDecimal discountValue,
                                   BigDecimal minOrderAmount, BigDecimal maxDiscountAmount,
                                   int maxUses, LocalDateTime expiresAt) {
            this(code, type, discountValue, minOrderAmount, maxDiscountAmount, maxUses,
                    "ALL", null, null, expiresAt);
        }
    }

    record ValidateResult(
            boolean valid,
            String message,
            BigDecimal discountAmount,
            BigDecimal finalAmount,
            Coupon coupon
    ) {}
}
