package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

public class RefundExceedsPaymentException extends RefundException {
    public RefundExceedsPaymentException(String message) {
        super(ErrorCode.REFUND_EXCEEDS_PAYMENT, message);
    }
}
