package github.lms.lemuel.order.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문 전 쿠폰 미리보기 — <b>결제와 같은 계산</b>을 재고 차감·주문 생성 없이 돌려본다.
 *
 * <p>미리보기가 따로 계산하면 안 되는 이유: 쿠폰의 적용 대상(전체/상품/카테고리)은 장바구니에
 * 무엇이 담겼는지를 알아야 정해진다. 금액만 넘기는 경로({@code GET /coupons/{code}/validate})는
 * 그걸 표현할 수 없어서, 상품 전용 쿠폰을 장바구니 전체에 적용한 값을 보여주게 된다. 그러면
 * 화면은 10,000 원 할인이라고 하고 결제는 1,000 원만 깎는 불일치가 생긴다.
 *
 * <p>그래서 이 경로는 주문 생성과 <b>같은 입력(상품 라인)</b>을 받고, 같은 상품 마스터에서
 * 단가·카테고리를 해석하며, 같은 {@code CouponUseCase.validateCoupon} 을 호출한다.
 */
public interface PreviewCouponUseCase {

    /**
     * @param couponCode 비어 있으면 쿠폰 없이 소계만 계산한다(할인 0, valid=true).
     * @param lines      주문에 쓸 라인과 동일한 형식. 재고는 건드리지 않는다.
     */
    Preview preview(Long userId, String couponCode, List<CreateMultiItemOrderUseCase.Line> lines);

    /**
     * @param subtotal       할인 전 라인 합
     * @param discountAmount 실제 깎이는 금액
     * @param eligibleAmount 할인 계산의 기준이 된 금액 = 쿠폰 대상에 맞는 라인들의 합.
     *                       화면이 "어느 상품에 적용됐는지" 를 설명할 수 있게 함께 돌려준다
     * @param finalAmount    소계 − 할인 (배송비 전)
     */
    record Preview(
            boolean valid,
            String message,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal eligibleAmount,
            BigDecimal finalAmount
    ) {}
}
