package github.lms.lemuel.point.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 포인트 계정·로트의 상태 전이 규칙 위반 (BusinessException 상속 — ErrorCode 가 HTTP 상태로 번역한다).
 *
 * <p>정지 계정에서 사용 시도, 잔액이 남은 계정 해지 시도, 종단 상태 로트의 재소비 등
 * <b>허용되지 않은 전이</b>를 도메인이 거부할 때 던진다.
 */
public class InvalidPointStateException extends BusinessException {

    private final String currentState;
    private final String attemptedOperation;

    public InvalidPointStateException(String message, String currentState, String attemptedOperation) {
        super(ErrorCode.POINT_INVALID_STATE, message);
        this.currentState = currentState;
        this.attemptedOperation = attemptedOperation;
    }

    public String getCurrentState() { return currentState; }
    public String getAttemptedOperation() { return attemptedOperation; }
}
