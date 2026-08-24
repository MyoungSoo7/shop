package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.Refund;

public interface SaveRefundPort {

    Refund save(Refund refund);
}
