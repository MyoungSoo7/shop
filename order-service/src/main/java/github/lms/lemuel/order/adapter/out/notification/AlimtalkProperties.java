package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 알림톡 설정 — {@code app.notification.alimtalk}.
 *
 * <pre>
 * app:
 *   notification:
 *     alimtalk:
 *       enabled: false            # 기본 꺼짐. 계약·템플릿 승인 전까지 켜지 않는다.
 *       templates:
 *         SHIPPING_STARTED: TPL_SHIP_001
 *         REFUND_RECEIVED:  TPL_RFND_001
 * </pre>
 *
 * <p><b>템플릿 표가 곧 발송 여부다.</b> 알림톡은 대행사에 사전 승인된 템플릿으로만 나가므로,
 * 코드가 보내고 싶어도 승인된 코드가 없으면 보낼 수 없다. 그래서 이 맵에 없는 사건은
 * 조용히 건너뛴다 — 승인이 끝난 사건부터 하나씩 켜는 것이 실제 운영 순서다.
 */
@ConfigurationProperties(prefix = "app.notification.alimtalk")
public class AlimtalkProperties {

    /** 채널 자체의 on/off. 기본 꺼짐. */
    private boolean enabled = false;

    /** 사건 → 승인된 템플릿 코드. 없는 사건은 발송 대상이 아니다. */
    private Map<OrderNotifiableEvent, String> templates = Map.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<OrderNotifiableEvent, String> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<OrderNotifiableEvent, String> templates) {
        this.templates = templates == null ? Map.of() : templates;
    }
}
