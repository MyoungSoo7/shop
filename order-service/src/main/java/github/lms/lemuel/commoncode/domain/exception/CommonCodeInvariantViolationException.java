package github.lms.lemuel.commoncode.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 공통코드 도메인 불변식 위반 — 그룹코드/그룹명/코드/코드명(label) 등 필수값 규칙을 어겼다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class CommonCodeInvariantViolationException extends BusinessException {

    public CommonCodeInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
