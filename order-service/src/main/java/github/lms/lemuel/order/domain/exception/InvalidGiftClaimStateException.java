package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.domain.GiftClaimStatus;

/**
 * 선물 수령의 상태머신 위반.
 *
 * <p>{@link InvalidReturnRequestStateException} 과 같은 이유로 별도 타입이다 — 상태 축이 다르면
 * 예외도 달라야 로그에서 어느 축의 위반인지 구분된다.
 */
public class InvalidGiftClaimStateException extends BusinessException {

    private final transient GiftClaimStatus from;

    public InvalidGiftClaimStateException(GiftClaimStatus from, String message) {
        super(ErrorCode.INVALID_STATE, from == null ? message : message + " (현재 수령 상태=" + from + ")");
        this.from = from;
    }

    public GiftClaimStatus getFrom() {
        return from;
    }
}
