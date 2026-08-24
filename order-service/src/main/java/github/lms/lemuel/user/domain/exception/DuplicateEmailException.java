package github.lms.lemuel.user.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class DuplicateEmailException extends BusinessException {

    public DuplicateEmailException(String email) {
        super(ErrorCode.DUPLICATE_EMAIL, "이미 존재하는 이메일입니다: " + email);
    }

    public DuplicateEmailException(String email, Throwable cause) {
        super(ErrorCode.DUPLICATE_EMAIL, "이미 존재하는 이메일입니다: " + email, cause);
    }
}
