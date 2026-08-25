package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import github.lms.lemuel.order.domain.OrderStatus;

/**
 * 주문 알림 전송 Port
 *
 * <p>오래 이 포트에는 {@link #sendOrderConfirmation} 하나뿐이었다. 그래서 고객은 주문 직후
 * 메일 한 통을 받고 그 뒤로는 아무 소식도 못 받았다 — 배송이 시작돼도, 환불 신청이 접수돼도,
 * 환불이 끝나도 통지가 없었다({@code ChangeOrderStatusService} 에는 알림 호출이 아예 없었다).
 *
 * <p>{@link #sendStatusChanged} 가 그 구멍을 메운다. 알림톡 같은 채널의 값어치는 주문 확인이
 * 아니라 이 생애주기 통지에 있으므로, 채널을 붙이기 전에 보낼 사건부터 만들어야 한다.
 */
public interface SendOrderNotificationPort {

    /** 주문 접수 확인. */
    void sendOrderConfirmation(String email, Order order);

    /**
     * 주문 상태가 바뀌었음을 알린다.
     *
     * <p>모든 전이가 통지 대상은 아니다 — 무엇을 고객에게 알릴지는 채널이 아니라
     * {@link OrderNotifiableEvent} 가 정한다. 알릴 것이 없는 전이면 조용히 지나간다.
     *
     * @param email    수신 이메일(모르면 null — 이메일 채널만 건너뛴다)
     * @param order    전이가 끝난 주문
     * @param previous 전이 전 상태(감사 이력에 남는 값과 같다)
     */
    void sendStatusChanged(String email, Order order, OrderStatus previous);
}
