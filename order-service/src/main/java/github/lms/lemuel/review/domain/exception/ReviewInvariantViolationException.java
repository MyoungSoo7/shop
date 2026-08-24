package github.lms.lemuel.review.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 리뷰 도메인 불변식 위반 — 평점 범위(1~5) 등 입력 규칙을 어겼다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class ReviewInvariantViolationException extends BusinessException {

    public ReviewInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
