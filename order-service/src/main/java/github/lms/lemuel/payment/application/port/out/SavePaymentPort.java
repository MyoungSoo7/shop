package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.PaymentDomain;

public interface SavePaymentPort {
    PaymentDomain save(PaymentDomain paymentDomain);
}
