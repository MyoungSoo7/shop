package github.lms.lemuel.shipping.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 배송 도메인 불변식 위반 — 배송지/운송장 필수값 규칙을 어겼다
 * (수령인·전화·우편번호·주소 필수, 출고 시 carrier·trackingNumber 필수).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 상태 전이 위반은 {@link InvalidShipmentStateException} 가 담당한다.
 */
public class ShipmentInvariantViolationException extends BusinessException {

    public ShipmentInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
