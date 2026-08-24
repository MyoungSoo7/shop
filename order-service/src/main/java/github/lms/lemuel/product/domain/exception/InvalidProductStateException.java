package github.lms.lemuel.product.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 상품 상태 위반 — 현재 상태에서 허용되지 않은 연산을 시도했다
 * (재고 부족 차감, 단종 SKU 차감, 단종 상품 활성/비활성, 삭제 이미지 대표 지정).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class InvalidProductStateException extends BusinessException {

    public InvalidProductStateException(String message) {
        super(ErrorCode.INVALID_STATE, message);
    }
}
