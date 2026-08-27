package github.lms.lemuel.wishlist.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 찜 도메인 불변식 위반 — 사용자·상품 식별자 누락, 보관 한도 초과.
 *
 * <p>{@code IllegalArgumentException} 을 쓰지 않는 이유는 OO 게이트가 금융 5서비스 도메인에서
 * 그것을 금지하기 때문만이 아니다. 도메인 예외는 "무엇이 어긋났는가"를 타입으로 말하므로,
 * 웹 계층이 메시지 문자열을 뜯어보지 않고도 응답을 결정할 수 있다.
 */
public class WishlistInvariantViolationException extends BusinessException {

    public WishlistInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
