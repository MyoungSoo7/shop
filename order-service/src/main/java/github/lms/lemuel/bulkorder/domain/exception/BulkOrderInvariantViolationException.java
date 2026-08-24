package github.lms.lemuel.bulkorder.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 대량주문 초안 불변식 위반. */
public class BulkOrderInvariantViolationException extends BusinessException {
    public BulkOrderInvariantViolationException(String message) {
        super(ErrorCode.BULK_ORDER_INVARIANT, message);
    }
}
