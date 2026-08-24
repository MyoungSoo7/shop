package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;

import java.util.Optional;

public interface GetPaymentPort {
    PaymentDomain getPayment(Long paymentId);

    /**
     * 주문 ID로 결제를 조회한다.
     *
     * @param orderId 주문 ID
     * @return 해당 주문의 결제 도메인
     * @throws github.lms.lemuel.payment.domain.exception.PaymentNotFoundException 결제가 없으면
     */
    PaymentDomain getPaymentByOrderId(Long orderId);

    /**
     * 주문 ID로 결제를 조회하되, 없으면 빈 Optional 을 반환한다(미결제 주문 취소 등에서 사용).
     */
    Optional<PaymentDomain> findByOrderId(Long orderId);
}
