package github.lms.lemuel.giftcard.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import java.math.BigDecimal;

/**
 * 기프트카드 잔액 부족 — 비즈니스 정상 결과다.
 *
 * <p>결제 경로에서는 "상품권으로는 결제할 수 없다"는 답이며 재시도 대상이 아니다.
 */
public class InsufficientGiftCardBalanceException extends BusinessException {

    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientGiftCardBalanceException(String message, BigDecimal requested, BigDecimal available) {
        super(ErrorCode.GIFT_CARD_INSUFFICIENT, message);
        this.requested = requested;
        this.available = available;
    }

    public BigDecimal getRequested() { return requested; }
    public BigDecimal getAvailable() { return available; }
}
