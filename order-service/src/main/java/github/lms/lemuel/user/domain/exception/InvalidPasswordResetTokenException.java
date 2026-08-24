package github.lms.lemuel.user.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class InvalidPasswordResetTokenException extends BusinessException {

    public InvalidPasswordResetTokenException(String message) {
        super(ErrorCode.INVALID_PASSWORD_RESET_TOKEN, message);
    }

    public InvalidPasswordResetTokenException() {
        super(ErrorCode.INVALID_PASSWORD_RESET_TOKEN, "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다.");
    }
}
