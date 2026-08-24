package github.lms.lemuel.giftcard.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import java.math.BigDecimal;

/**
 * 기프트카드 금액이 도메인 규약을 어겼을 때 — 양수 + 1원 단위 정수.
 */
public class InvalidGiftCardAmountException extends BusinessException {

    private final String operation;
    private final BigDecimal amount;

    public InvalidGiftCardAmountException(String message, String operation, BigDecimal amount) {
        super(ErrorCode.GIFT_CARD_INVALID_AMOUNT, message);
        this.operation = operation;
        this.amount = amount;
    }

    public String getOperation() { return operation; }
    public BigDecimal getAmount() { return amount; }
}
