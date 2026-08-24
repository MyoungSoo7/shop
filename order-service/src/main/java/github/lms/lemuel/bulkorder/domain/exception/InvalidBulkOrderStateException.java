package github.lms.lemuel.bulkorder.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 대량주문 상태 전이 위반. */
public class InvalidBulkOrderStateException extends BusinessException {
    public InvalidBulkOrderStateException(String message) {
        super(ErrorCode.INVALID_BULK_ORDER_STATE, message);
    }
}
