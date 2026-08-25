package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 알림톡 주문 알림 채널 — {@link OrderNotificationChannel} 의 카카오 알림톡 구현.
 *
 * <p><b>수신 번호는 회원 정보가 아니라 {@link ShippingAddressSnapshot#phone() 주문 시점 배송지의
 * 번호}에서 나온다.</b> 회원 전화번호를 쓰면 "선물로 남에게 보낸 주문"의 통지가 받는 사람이 아니라
 * 결제자에게 가고, 회원이 번호를 바꾸면 과거 주문의 통지 대상까지 소급해 바뀐다. 주문서에 굳어 있는
 * 번호가 그 주문에 대해 맞는 번호다.
 *
 * <p>번호가 없는 주문(스냅샷 도입 전 레거시, 단건 주문 경로)은 <b>조용히 건너뛴다</b>. 알림 하나
 * 못 보낸 것이 주문 처리를 막을 이유는 없다.
 *
 * <p>주문 접수 확인({@link #sendOrderConfirmation})은 이 채널이 보내지 않는다. 그 시점의 통지는
 * 이미 메일이 담당하고 있고, 알림톡의 값어치는 접수 확인이 아니라 그 뒤의 배송·환불 통지에 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.alimtalk.enabled", havingValue = "true")
@EnableConfigurationProperties(AlimtalkProperties.class)
public class AlimtalkOrderNotificationChannel implements OrderNotificationChannel {

    private final AlimtalkSender sender;
    private final AlimtalkProperties properties;

    public AlimtalkOrderNotificationChannel(AlimtalkSender sender, AlimtalkProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public String channelName() {
        return "alimtalk";
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public void sendOrderConfirmation(String email, Order order) {
        // 접수 확인은 메일의 몫 — 여기서 겹쳐 보내면 고객이 같은 사건으로 두 번 알림을 받는다.
    }

    @Override
    public void sendStatusChanged(String email, Order order, OrderNotifiableEvent event) {
        String templateCode = properties.getTemplates().get(event);
        if (templateCode == null) {
            // 승인된 템플릿이 없는 사건 — 대행사가 거절할 발송을 시도하지 않는다.
            log.debug("승인 템플릿 없음, 알림톡 생략: orderId={}, event={}", order.getId(), event);
            return;
        }

        String phone = phoneOf(order);
        if (phone == null) {
            log.debug("배송지 번호가 없어 알림톡 생략: orderId={}, event={}", order.getId(), event);
            return;
        }

        sender.send(phone, templateCode, render(order, event));
        log.info("알림톡 발송 완료: orderId={}, event={}, template={}", order.getId(), event, templateCode);
    }

    private static String phoneOf(Order order) {
        ShippingAddressSnapshot address = order.getShippingAddress();
        return address == null ? null : address.phone();
    }

    /** 승인 템플릿 본문과 같은 형태로 변수만 채운다. */
    private static String render(Order order, OrderNotifiableEvent event) {
        return "[Lemuel] %s%n주문번호: %d%n결제금액: %s원".formatted(
                event.summary(), order.getId(), order.getAmount());
    }
}
