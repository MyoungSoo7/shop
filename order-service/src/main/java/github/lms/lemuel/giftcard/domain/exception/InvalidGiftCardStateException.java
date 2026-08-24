package github.lms.lemuel.giftcard.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 기프트카드 상태 전이 규칙 위반 — 미활성 카드 등록, 이미 등록된 카드 재등록,
 * 만료 전 소멸 시도 등 <b>허용되지 않은 전이</b>를 도메인이 거부할 때.
 */
public class InvalidGiftCardStateException extends BusinessException {

    private final String currentState;
    private final String attemptedOperation;

    public InvalidGiftCardStateException(String message, String currentState, String attemptedOperation) {
        super(ErrorCode.GIFT_CARD_INVALID_STATE, message);
        this.currentState = currentState;
        this.attemptedOperation = attemptedOperation;
    }

    public String getCurrentState() { return currentState; }
    public String getAttemptedOperation() { return attemptedOperation; }
}
