package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Slack 주문 알림 채널 — {@link OrderNotificationChannel} 의 Slack Incoming Webhook 구현.
 *
 * <p>{@code app.notification.slack.enabled=true} 일 때만 빈으로 등록(opt-in). 신규 주문을 운영 채널에
 * 실시간 통지하는 용도다. 전송 실패는 예외로 던지고 격리는 {@link CompositeOrderNotificationAdapter} 가 한다.
 *
 * <p>메일 채널과 나란히 등록되어 디스패처가 동시에 팬아웃한다 — Strategy + Composite 의 확장 지점 예시.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.slack.enabled", havingValue = "true")
public class SlackOrderNotificationChannel implements OrderNotificationChannel {

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackOrderNotificationChannel(
            @Value("${app.notification.slack.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Override
    public String channelName() {
        return "slack";
    }

    @Override
    public boolean isEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public void sendOrderConfirmation(String email, Order order) {
        String text = String.format(":shopping_trolley: 새 주문 #%d — 상품 %d, 결제 %s원 (%s)",
                order.getId(), order.getProductId(), order.getAmount(), email);

        restClient.post()
                .uri(webhookUrl)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();

        log.info("주문 확인 Slack 통지 완료: orderId={}", order.getId());
    }
}
