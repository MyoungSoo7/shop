package github.lms.lemuel.menu.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 메뉴 도메인 불변식 위반 — 메뉴 이름 필수 등 입력 규칙을 어겼다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class MenuInvariantViolationException extends BusinessException {

    public MenuInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
