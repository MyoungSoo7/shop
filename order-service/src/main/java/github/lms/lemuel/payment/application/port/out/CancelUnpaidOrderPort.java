package github.lms.lemuel.payment.application.port.out;

/**
 * 미결제 주문 취소 포트 — payment 컨텍스트가 order 컨텍스트에 취소를 요청한다.
 *
 * <p>주문이 이미 결제·취소된 뒤라면(결제 행만 잔류하는 경우) 주문을 건드리지 않고 {@code false} 를 돌려준다.
 * 잔류 결제 정리가 정상 주문을 취소하는 일은 없어야 한다.
 */
public interface CancelUnpaidOrderPort {

    /**
     * @return 실제로 주문을 취소했으면 true, 취소 가능한 상태가 아니어서 손대지 않았으면 false
     */
    boolean cancelUnpaidOrder(Long orderId, String reason);
}
