package github.lms.lemuel.order.application.port.out;

import java.math.BigDecimal;

/**
 * 주문 컨텍스트가 결제 컨텍스트에 환불 실행을 요청하기 위한 아웃바운드 포트.
 *
 * <p>관리자 환불 승인(approveRefund)이 단순 상태 변경에 그치지 않고 실제 PG 환불·정산 조정으로
 * 이어지도록 배선하는 연결점이다. payment 가 order 를 호출할 때 {@code UpdateOrderStatusPort} 를
 * 쓰는 것과 대칭으로, order 도 payment 의 내부 JPA/엔티티를 직접 참조하지 않고 이 포트(어댑터가
 * payment 의 inbound use case 로 위임)만 사용한다.
 */
public interface RefundOrderPaymentPort {

    /**
     * 주문에 연결된 결제를 전액 환불한다.
     *
     * <p>환불이 성공하면 payment 가 주문 상태를 {@code REFUNDED} 로 전이하고 {@code PaymentRefunded}
     * 이벤트를 발행한다(→ settlement 역정산 조정). PG 환불이 실패하면 예외가 전파되어 호출 트랜잭션
     * 전체가 롤백된다 — "환불에 성공한 경우에만 주문이 확정"되는 보장을 제공한다.
     *
     * @param orderId 환불 대상 주문 ID
     */
    default void refundOrderPaymentFully(Long orderId) {
        refundOrderPayment(orderId, null, null);
    }

    /**
     * 주문에 연결된 결제를 지정 금액만큼 환불한다(배송비 차감 등 부분 환불 지원).
     *
     * @param orderId        환불 대상 주문 ID
     * @param amount         환불 금액 — {@code null} 이면 전액 환불
     * @param idempotencyKey 부분 환불 멱등 키 — 부분 환불 시 필수(같은 키 재시도는 중복 환불되지 않음).
     *                       전액 환불(amount=null)이면 {@code null} 허용(payment 가 기본 키 생성).
     */
    void refundOrderPayment(Long orderId, BigDecimal amount, String idempotencyKey);

    /**
     * 주문에 <b>결제가 존재하고 환불 가능(CAPTURED)</b>하면 전액 환불하고 {@code true} 를 반환한다.
     * 미결제 주문(결제 없음)이나 이미 환불/미캡처 상태면 아무 것도 하지 않고 {@code false} 를 반환한다.
     *
     * <p>취소 승인처럼 "결제됐으면 환불, 아니면 그냥 취소" 분기가 필요한 경우에 사용한다.
     *
     * @return 실제 환불이 실행됐으면 {@code true}, 환불 대상이 없으면 {@code false}
     */
    boolean refundOrderPaymentFullyIfPresent(Long orderId);
}
