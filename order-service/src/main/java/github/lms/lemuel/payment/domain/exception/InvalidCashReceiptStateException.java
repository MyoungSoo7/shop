package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 현금영수증 상태 전이 위반. */
public class InvalidCashReceiptStateException extends BusinessException {
    public InvalidCashReceiptStateException(String message) {
        super(ErrorCode.INVALID_CASH_RECEIPT_STATE, message);
    }
}
