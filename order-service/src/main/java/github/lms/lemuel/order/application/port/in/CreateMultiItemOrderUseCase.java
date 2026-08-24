package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.util.List;

public interface CreateMultiItemOrderUseCase {

    /**
     * 다건 주문 생성. {@code couponCode} 가 주어지면 쿠폰 검증·할인 반영·사용 기록을
     * 주문 생성과 <b>같은 트랜잭션</b>에서 처리하여, 쿠폰 실패 시 주문·재고 차감까지 모두 롤백한다.
     *
     * @param couponCode 적용할 쿠폰 코드. 없으면 {@code null}/빈 문자열
     */
    Order create(Long userId, List<Line> lines, String couponCode);

    /** 쿠폰 없는 다건 주문 (기존 호출 호환). */
    default Order create(Long userId, List<Line> lines) {
        return create(userId, lines, null);
    }

    /**
     * 주문 생성 요청에서 들어오는 1 라인.
     *
     * @param productId  상품 ID (필수)
     * @param variantId  옵션(SKU) 사용 시 지정. 없으면 null — 단일 상품
     * @param quantity   수량 (양수)
     */
    record Line(Long productId, Long variantId, int quantity) {
        public Line {
            if (productId == null) throw new OrderInvariantViolationException("productId 필수");
            if (quantity <= 0) throw new OrderInvariantViolationException("quantity 는 양수");
        }
    }
}
