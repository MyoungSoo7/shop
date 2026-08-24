package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

/**
 * Command object for creating a new payment
 */
public class CreatePaymentCommand {
    private final Long orderId;
    private final String paymentMethod;

    public CreatePaymentCommand(Long orderId, String paymentMethod) {
        if (orderId == null) {
            throw new PaymentInvariantViolationException("orderId must not be null");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new PaymentInvariantViolationException("paymentMethod must not be blank");
        }
        this.orderId = orderId;
        this.paymentMethod = paymentMethod;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }
}
