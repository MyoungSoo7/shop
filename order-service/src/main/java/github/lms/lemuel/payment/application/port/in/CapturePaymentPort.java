package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;

public interface CapturePaymentPort {
    PaymentDomain capturePayment(Long paymentId);
}
