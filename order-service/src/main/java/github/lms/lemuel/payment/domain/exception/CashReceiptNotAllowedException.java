package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 현금영수증 발급 대상·형식 위반. */
public class CashReceiptNotAllowedException extends BusinessException {
    public CashReceiptNotAllowedException(String message) {
        super(ErrorCode.CASH_RECEIPT_NOT_ALLOWED, message);
    }
}
