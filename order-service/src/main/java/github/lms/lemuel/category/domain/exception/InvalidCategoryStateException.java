package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 카테고리 상태 위반 — 현재 상태에서 허용되지 않은 연산을 시도했다
 * (이동 시 최대 깊이 초과, 삭제된 카테고리 활성화).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class InvalidCategoryStateException extends BusinessException {

    public InvalidCategoryStateException(String message) {
        super(ErrorCode.INVALID_STATE, message);
    }
}
