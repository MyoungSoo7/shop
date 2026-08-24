package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.payment.domain.PaymentStatus;

public class InvalidPaymentStateException extends BusinessException {
    public InvalidPaymentStateException(PaymentStatus currentStatus, PaymentStatus requiredStatus) {
        super(ErrorCode.INVALID_PAYMENT_STATE,
                String.format("Invalid payment state. Current: %s, Required: %s", currentStatus, requiredStatus));
    }

    public InvalidPaymentStateException(String message) {
        super(ErrorCode.INVALID_PAYMENT_STATE, message);
    }
}
