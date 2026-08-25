package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.util.List;

public interface CreateMultiItemOrderUseCase {

    /**
     * 다건 주문 생성. {@code couponCode} 가 주어지면 쿠폰 검증·할인 반영·사용 기록을
     * 주문 생성과 <b>같은 트랜잭션</b>에서 처리하여, 쿠폰 실패 시 주문·재고 차감까지 모두 롤백한다.
     *
     * <p>{@code shippingAddress} 가 주어지면 주문서에 배송지 스냅샷을 굳히고, <b>같은 트랜잭션에서</b>
     * 배송(PENDING)까지 생성한다. 둘을 갈라 놓으면 "배송지 없는 주문"이 남아 운영자가 그 주문만
     * 따로 찾아 손으로 채워야 한다 — 대량주문 경로가 이미 같은 이유로 한 트랜잭션에 묶여 있다.
     *
     * @param couponCode      적용할 쿠폰 코드. 없으면 {@code null}/빈 문자열
     * @param shippingAddress 주문 시점 배송지. {@code null} 이면 배송을 만들지 않는다(배송지를 받지 않는 경로)
     */
    Order create(Long userId, List<Line> lines, String couponCode, ShippingAddressSnapshot shippingAddress);

    /** 배송지 없이 만드는 다건 주문 (배송을 별도 경로에서 붙이는 호출 호환). */
    default Order create(Long userId, List<Line> lines, String couponCode) {
        return create(userId, lines, couponCode, null);
    }

    /** 쿠폰 없는 다건 주문 (기존 호출 호환). */
    default Order create(Long userId, List<Line> lines) {
        return create(userId, lines, null, null);
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
