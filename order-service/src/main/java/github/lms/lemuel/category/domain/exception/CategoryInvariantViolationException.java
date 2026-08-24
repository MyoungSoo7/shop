package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 카테고리 도메인 불변식 위반 — 이름/슬러그 형식·길이, 깊이 범위, 자기부모(순환) 등 입력 규칙을 어겼다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 이동/활성화 시점의 상태 위반은 {@link InvalidCategoryStateException} 가 담당한다.
 */
public class CategoryInvariantViolationException extends BusinessException {

    public CategoryInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
