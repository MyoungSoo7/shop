package github.lms.lemuel.product.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 상품 도메인 불변식 위반 — 상품/변형(SKU)/이미지/태그/카테고리의 이름·가격·재고·수량·형식 규칙을 어겼다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 상태 전이·재고 부족 등 상태 위반은 {@link InvalidProductStateException} 가 담당한다.
 */
public class ProductInvariantViolationException extends BusinessException {

    public ProductInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }

    /** 원인 예외(예: 옵션 트리 JSON 파싱 실패)를 보존하는 경로. */
    public ProductInvariantViolationException(String message, Throwable cause) {
        super(ErrorCode.INVALID_ARGUMENT, message, cause);
    }
}
