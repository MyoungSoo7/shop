package github.lms.lemuel.bulkorder.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 대량주문 초안 미존재. */
public class BulkOrderNotFoundException extends BusinessException {
    public BulkOrderNotFoundException(String message) {
        super(ErrorCode.BULK_ORDER_NOT_FOUND, message);
    }
}
