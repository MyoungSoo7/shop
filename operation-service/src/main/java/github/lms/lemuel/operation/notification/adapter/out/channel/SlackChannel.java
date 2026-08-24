package github.lms.lemuel.operation.notification.adapter.out.channel;

import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Slack incoming webhook 으로 게시한다. {@code SLACK_WEBHOOK_URL} 이 설정된 경우에만 활성이라,
 * Slack 설정이 없는 테스트·컨테이너에서도 서비스가 그대로 뜬다.
 */
@Component
public class SlackChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger("notification.channel.slack");
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 299;

    private final String webhookUrl;
    private final HttpClient http;

    public SlackChannel(@Value("${app.channels.slack.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public boolean isEnabled() {
        return !webhookUrl.isBlank();
    }

    @Override
    public void send(Notification notification) throws Exception {
        String text = NotificationTemplate.renderPlainText(notification);
        String payload = "{\"text\":%s}".formatted(jsonString(text));

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < HTTP_OK_MIN || response.statusCode() > HTTP_OK_MAX) {
            throw new IllegalStateException(
                    "slack webhook returned %d: %s".formatted(response.statusCode(), response.body()));
        }
        log.debug("slack delivered status={}", response.statusCode());
    }

    /** 필드 하나 때문에 jackson 을 끌어오지 않는 최소 JSON 문자열 이스케이퍼. */
    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
