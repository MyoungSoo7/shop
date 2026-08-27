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
     * <p>{@code consent} 가 주어지면 주문 시점 동의를 <b>같은 트랜잭션에서</b> 기록한다. 필수 동의가
     * 빠졌으면 여기서 예외가 나고 주문·재고 차감까지 전부 롤백된다. 동의를 별도 호출로 빼면
     * "주문은 생겼는데 동의 기록은 없는" 주문이 생기는데, 그 주문은 나중에 근거로 쓸 수 없다.
     *
     * @param couponCode      적용할 쿠폰 코드. 없으면 {@code null}/빈 문자열
     * @param shippingAddress 주문 시점 배송지. {@code null} 이면 배송을 만들지 않는다(배송지를 받지 않는 경로)
     * @param consent         주문 시점 동의. {@code null} 이면 <b>이 경로는 아직 동의를 받지 않는다</b>는
     *                        뜻이고, 어느 경로가 그런지는 {@code order-consent-gate} 가 이름으로 붙들고
     *                        있다. 빈 목록은 뜻이 다르다 — 받기는 하는데 아무것도 안 왔다는 것이라
     *                        필수 항목 누락으로 거절된다
     * @param destinationGroupId 여러 곳 배송 묶음 id. {@code null} 이면 배송지가 하나뿐인 보통의 주문이다.
     *                        이 값이 있으면 같은 값을 가진 다른 주문들과 한 번의 결제에서 나온 형제가 된다
     */
    Order create(Long userId, List<Line> lines, String couponCode,
                 ShippingAddressSnapshot shippingAddress, ConsentSubmission consent,
                 String destinationGroupId);

    /** 배송지가 하나뿐인 보통의 주문 (묶음 개념이 없던 기존 호출 호환). */
    default Order create(Long userId, List<Line> lines, String couponCode,
                         ShippingAddressSnapshot shippingAddress, ConsentSubmission consent) {
        return create(userId, lines, couponCode, shippingAddress, consent, null);
    }

    /** 동의를 받지 않는 경로 (배송지만 있는 기존 호출 호환). */
    default Order create(Long userId, List<Line> lines, String couponCode,
                         ShippingAddressSnapshot shippingAddress) {
        return create(userId, lines, couponCode, shippingAddress, null);
    }

    /** 배송지 없이 만드는 다건 주문 (배송을 별도 경로에서 붙이는 호출 호환). */
    default Order create(Long userId, List<Line> lines, String couponCode) {
        return create(userId, lines, couponCode, null, null);
    }

    /** 쿠폰 없는 다건 주문 (기존 호출 호환). */
    default Order create(Long userId, List<Line> lines) {
        return create(userId, lines, null, null, null);
    }

    /**
     * 결제 화면에서 올라온 동의 묶음.
     *
     * <p>주문 번호를 아직 모르는 시점의 값이라 {@code RecordCommand} 와 따로 있다 — 번호는 주문이
     * 저장된 뒤에 붙는다.
     *
     * @param ipAddress <b>서버가 관찰한</b> 접속지. 클라이언트가 보낸 값을 그대로 받으면 증명하려는
     *                  사실을 증명 대상이 스스로 적는 셈이 되므로, 어댑터에서 채워 넣는다
     */
    record ConsentSubmission(List<RecordOrderConsentUseCase.Acceptance> acceptances, String ipAddress) {
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
