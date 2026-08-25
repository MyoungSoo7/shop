package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import github.lms.lemuel.order.domain.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 주문 알림 디스패처 — <b>Composite + Strategy</b>.
 *
 * <p>{@link SendOrderNotificationPort} 의 유일한 구현으로서, 활성화된 모든
 * {@link OrderNotificationChannel}(메일·Slack 등)에 알림을 팬아웃한다.
 *
 * <p><b>채널 간 실패 격리</b>: 한 채널이 실패해도 나머지 채널은 계속 시도하며, 알림 실패가
 * 주문 생성 트랜잭션을 롤백시키지 않는다(예외를 삼키고 로그만). 새 채널은 {@link OrderNotificationChannel}
 * 구현을 추가하기만 하면 이 클래스 수정 없이 자동 합류한다(Open/Closed).
 */
@Slf4j
@Component
public class CompositeOrderNotificationAdapter implements SendOrderNotificationPort {

    private final List<OrderNotificationChannel> channels;

    public CompositeOrderNotificationAdapter(List<OrderNotificationChannel> channels) {
        this.channels = channels;
    }

    @Override
    public void sendOrderConfirmation(String email, Order order) {
        fanOut(order, channel -> channel.sendOrderConfirmation(email, order));
    }

    /**
     * {@inheritDoc}
     *
     * <p>알릴 사건인지의 판정을 여기서 한 번만 한다. 채널마다 전이표를 다시 읽게 두면 채널이 늘 때마다
     * "메일은 울렸는데 알림톡은 안 울린다" 류의 어긋남이 생긴다.
     */
    @Override
    public void sendStatusChanged(String email, Order order, OrderStatus previous) {
        Optional<OrderNotifiableEvent> event = OrderNotifiableEvent.of(previous, order.getStatus());
        if (event.isEmpty()) {
            return;
        }
        fanOut(order, channel -> channel.sendStatusChanged(email, order, event.get()));
    }

    /** 활성 채널에 팬아웃하되 한 채널의 실패를 다른 채널·호출 트랜잭션으로 번지지 않게 가둔다. */
    private void fanOut(Order order, Consumer<OrderNotificationChannel> send) {
        for (OrderNotificationChannel channel : channels) {
            if (!channel.isEnabled()) {
                continue;
            }
            try {
                send.accept(channel);
            } catch (Exception e) {
                // 채널 실패 격리 — 다른 채널·주문 생성에 영향 없음
                log.error("알림 채널 '{}' 전송 실패: orderId={}, error={}",
                        channel.channelName(), order.getId(), e.getMessage());
            }
        }
    }
}
