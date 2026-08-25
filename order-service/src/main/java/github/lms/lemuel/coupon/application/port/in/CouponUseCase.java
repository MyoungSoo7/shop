package github.lms.lemuel.coupon.application.port.in;

import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import github.lms.lemuel.coupon.domain.DiscountTargetLine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface CouponUseCase {

    Coupon createCoupon(CreateCouponCommand command);

    /**
     * 쿠폰 검증: 코드, 사용자 중복 사용 여부, 주문 금액 조건, <b>적용 대상</b> 확인.
     * 유효하면 할인 금액과 {@link Coupon} 을 함께 돌려준다.
     *
     * <p>장바구니를 줄 단위로 받는 이유는 대상 때문이다. 이전에는 소계 하나만 받아서
     * {@code coupon.calculateDiscount(소계)} 를 불렀고, {@code targetType} 은 목록 필터
     * ({@link #getAvailableCoupons})에서만 쓰여 <b>결제 시점에는 아무 효력이 없었다</b> —
     * 특정 상품 전용 10% 쿠폰이 장바구니 전체를 10% 깎았다(실측: 1,000 이어야 할 할인이 10,000).
     *
     * <p>최소 주문 금액은 여전히 <b>소계 전체</b> 기준이다("3만원 이상 구매 시 A상품 10%").
     * 할인 계산만 대상 라인 합({@link Coupon#eligibleBase})을 기준으로 한다.
     */
    ValidateResult validateCoupon(String code, Long userId, List<DiscountTargetLine> lines);

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

    /**
     * @param discountAmount 실제 깎이는 금액
     * @param finalAmount    소계 − 할인 (배송비 전)
     * @param eligibleAmount 할인 계산의 기준이 된 금액 = 대상에 맞는 라인들의 합.
     *                       {@code ALL} 쿠폰이면 소계와 같다. 주문은 이 금액이 걸린 라인들에만
     *                       할인을 안분해야 한다 — 전체에 안분하면 대상 밖 라인이 깎이지 않은 값을
     *                       치르고도 할인 몫을 짊어져 부분 취소 환불이 어긋난다
     */
    record ValidateResult(
            boolean valid,
            String message,
            BigDecimal discountAmount,
            BigDecimal finalAmount,
            BigDecimal eligibleAmount,
            Coupon coupon
    ) {}
}
