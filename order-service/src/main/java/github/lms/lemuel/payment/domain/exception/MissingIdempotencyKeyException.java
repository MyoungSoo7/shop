package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

public class MissingIdempotencyKeyException extends RefundException {
    public MissingIdempotencyKeyException(String message) {
        super(ErrorCode.MISSING_IDEMPOTENCY_KEY, message);
    }
}
