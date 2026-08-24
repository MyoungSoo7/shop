package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.OrderStatus;

import java.util.Optional;

/**
 * 상태 이력 조회 — 신청 철회가 "직전 상태"를 알아내는 유일한 근거.
 *
 * <p>주문 행에 {@code status_before_request} 같은 필드를 새로 두지 않는다. 그 값은 이미
 * {@code order_status_history} 가 사실로 갖고 있고, 두 곳에 같은 사실을 두면 둘이 어긋나는 날이 온다.
 */
public interface LoadOrderStatusHistoryPort {

    /**
     * 이 주문이 {@code currentStatus} 로 들어올 때의 직전 상태(가장 최근 기록).
     *
     * @return 이력이 없으면 비어 있음 — 호출자는 추측하지 말고 실패해야 한다
     */
    Optional<OrderStatus> findPreviousStatus(Long orderId, OrderStatus currentStatus);
}
