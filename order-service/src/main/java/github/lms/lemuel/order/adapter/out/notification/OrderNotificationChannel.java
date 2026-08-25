package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;

/**
 * 주문 알림 전송 채널 — <b>Strategy 패턴</b>.
 *
 * <p>이메일·Slack 등 채널별 전송 방식을 각 구현이 캡슐화한다. 디스패처
 * ({@link CompositeOrderNotificationAdapter})가 활성화된 채널들에 팬아웃하므로,
 * 새 채널(SMS·푸시 등)을 추가할 때 디스패처/호출부 수정 없이 이 인터페이스 구현만 추가하면 된다.
 */
public interface OrderNotificationChannel {

    /** 채널 식별자 (로깅·관측용). */
    String channelName();

    /** 현재 이 채널이 전송 가능한 상태인지. 비활성이면 디스패처가 건너뛴다. */
    boolean isEnabled();

    /**
     * 주문 확정 알림 전송. 실패 시 예외를 던지며, 채널 간 격리는 디스패처가 책임진다
     * (한 채널 실패가 다른 채널·주문 생성 트랜잭션에 영향 없음).
     */
    void sendOrderConfirmation(String email, Order order);

    /**
     * 주문 생애주기 사건 알림 전송(배송 시작·환불 접수 등).
     *
     * <p>어떤 전이가 알릴 만한 사건인지는 이미 {@link OrderNotifiableEvent} 가 판정해서 넘겨준다.
     * 채널은 "무엇을 알릴까"가 아니라 "어떻게 보낼까"만 결정한다 — 채널마다 울리는 사건이 달라지면
     * 그건 기능이 아니라 버그로 읽히기 때문이다.
     *
     * <p>{@link #sendOrderConfirmation} 과 마찬가지로 실패 시 예외를 던지고, 채널 간 격리는
     * {@link CompositeOrderNotificationAdapter} 가 책임진다.
     */
    void sendStatusChanged(String email, Order order, OrderNotifiableEvent event);
}
