package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 결제 1건당 유효 현금영수증 중복. */
public class DuplicateCashReceiptException extends BusinessException {
    public DuplicateCashReceiptException(String message) {
        super(ErrorCode.DUPLICATE_CASH_RECEIPT, message);
    }
}
