package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;

public interface AuthorizePaymentPort {
    PaymentDomain authorizePayment(Long paymentId);
}
