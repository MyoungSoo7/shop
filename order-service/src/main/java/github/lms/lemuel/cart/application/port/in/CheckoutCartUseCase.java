package github.lms.lemuel.cart.application.port.in;

import github.lms.lemuel.order.domain.Order;

/**
 * 장바구니 → 주문 변환 (체크아웃).
 *
 * <p>흐름: 장바구니 라인을 다건 주문 line 으로 변환 → CreateMultiItemOrderUseCase 호출 →
 * 주문 생성 성공 시 장바구니 비우기.
 *
 * <p>실패 시 (재고 부족, PG 오류 등) 장바구니는 유지되어 사용자가 다시 시도 가능.
 */
public interface CheckoutCartUseCase {
    Order checkout(Long userId);
}
