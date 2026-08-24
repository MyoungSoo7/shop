package github.lms.lemuel.shipping.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.shipping.domain.ShippingStatus;

/**
 * 배송 상태머신 위반 — 허용되지 않은 전이({@code from → to})를 시도했다
 * (READY/SHIPPED/IN_TRANSIT/DELIVERED/RETURNED 전이, 배송지 변경은 PENDING 만).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이의 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidShipmentStateException extends BusinessException {

    private final transient ShippingStatus from;
    private final transient ShippingStatus to;

    public InvalidShipmentStateException(ShippingStatus from, ShippingStatus to) {
        super(ErrorCode.INVALID_STATE, "배송 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    public ShippingStatus getFrom() {
        return from;
    }

    public ShippingStatus getTo() {
        return to;
    }
}
