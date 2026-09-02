package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.OrderStatusChange;

import java.util.List;
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

    /**
     * 이 주문의 상태 변경 <b>전부</b>를 시간순(오래된 것 먼저)으로.
     *
     * <p>정렬을 오름차순으로 고정한 이유는 이 목록이 <b>읽는 순서 그대로 이야기</b>이기 때문이다 —
     * 결제됨 → 배송준비 → 배송중 처럼. 최신순으로 주면 화면마다 다시 뒤집게 되고, 뒤집는 걸
     * 빠뜨린 화면에서 인과가 거꾸로 읽힌다.
     *
     * <p>페이징을 두지 않았다. 한 주문의 상태 변경은 많아야 수십 건이고, 상한을 두면 "이력의 일부만
     * 보여 주는 이력 화면" 이라는 최악의 물건이 된다 — CS 가 찾는 그 한 줄이 잘린 쪽에 있을 수 있다.
     */
    List<OrderStatusChange> findHistory(Long orderId);
}
