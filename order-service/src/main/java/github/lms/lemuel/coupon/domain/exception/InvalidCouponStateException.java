package github.lms.lemuel.coupon.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 쿠폰 사용 상태 위반 — 사용 시점에 쿠폰을 적용할 수 없다
 * (비활성, 사용 한도 초과, 사용 개시 전, 만료, 최소 주문 금액 미달).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class InvalidCouponStateException extends BusinessException {

    public InvalidCouponStateException(String message) {
        super(ErrorCode.INVALID_STATE, message);
    }

    /** 동시 사용 UNIQUE 위반 등 원인 예외를 보존하는 경로. */
    public InvalidCouponStateException(String message, Throwable cause) {
        super(ErrorCode.INVALID_STATE, message, cause);
    }
}
