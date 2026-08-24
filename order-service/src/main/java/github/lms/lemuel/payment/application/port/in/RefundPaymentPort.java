package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;

import java.math.BigDecimal;

public interface RefundPaymentPort {

    /**
     * 부분 또는 전체 환불을 수행한다.
     *
     * @param paymentId 결제 ID
     * @param amount 환불 금액 — null 이면 전액 환불
     * @param idempotencyKey 클라이언트 제공 멱등 키 — null 이면 전액 환불 기본 키(payment-{id}-full) 사용.
     *                       부분 환불은 호출자가 고유 키를 반드시 지정해야 같은 금액으로 재시도해도 중복 환불되지 않는다.
     * @return 업데이트된 결제 도메인
     */
    PaymentDomain refundPayment(Long paymentId, BigDecimal amount, String idempotencyKey);

    /**
     * 레거시 호환 — 전액 환불 단축 호출
     */
    default PaymentDomain refundPayment(Long paymentId) {
        return refundPayment(paymentId, null, null);
    }
}
