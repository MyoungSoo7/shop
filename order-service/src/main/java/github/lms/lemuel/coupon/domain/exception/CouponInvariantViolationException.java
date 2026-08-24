package github.lms.lemuel.coupon.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 쿠폰 도메인 불변식 위반 — 코드/타입/할인/한도/대상/기간 등 생성·설정 규칙을 어겼다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 사용 시점의 유효성(비활성·소진·기간·최소금액) 위반은 {@link InvalidCouponStateException} 가 담당한다.
 */
public class CouponInvariantViolationException extends BusinessException {

    public CouponInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
