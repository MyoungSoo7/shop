package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class UserNotExistsException extends BusinessException {

    public UserNotExistsException(Long userId) {
        super(ErrorCode.USER_NOT_EXISTS, "존재하지 않는 사용자입니다: " + userId);
    }

    public UserNotExistsException(Long userId, Throwable cause) {
        super(ErrorCode.USER_NOT_EXISTS, "존재하지 않는 사용자입니다: " + userId, cause);
    }
}
