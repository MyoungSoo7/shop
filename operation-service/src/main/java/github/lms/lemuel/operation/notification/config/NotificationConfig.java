package github.lms.lemuel.operation.notification.config;

import github.lms.lemuel.operation.notification.adapter.out.dedupe.InMemoryTtlDedupeStore;
import github.lms.lemuel.operation.notification.adapter.out.stream.InMemoryNotificationStream;
import github.lms.lemuel.operation.notification.application.port.out.DedupeStore;
import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.application.port.out.NotificationStream;
import github.lms.lemuel.operation.notification.application.service.NotificationDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 알림 슬라이스의 코어를 조립한다. 채널은 스프링이 발견해서({@link NotificationChannel} 을 구현한
 * 모든 {@code @Component}) 리스트로 주입하므로, 채널 추가에 이 클래스 수정이 필요 없다.
 */
@Configuration
public class NotificationConfig {

    @Bean
    public DedupeStore notificationDedupeStore(@Value("${app.dedupe.ttl-minutes:30}") long ttlMinutes) {
        return new InMemoryTtlDedupeStore(Duration.ofMinutes(ttlMinutes), java.time.Instant::now);
    }

    /**
     * SSE 푸시 포트. {@code @Component} 가 아니라 여기서 등록하는 이유는 보존 상한을 설정으로
     * 남겨 두기 위함이고, 덕분에 아웃바운드 어댑터가 테스트용 평범한 생성자를 유지한다.
     */
    @Bean
    public NotificationStream notificationStream(
            @Value("${app.stream.buffer-per-recipient:100}") int bufferPerRecipient,
            @Value("${app.stream.max-recipients:10000}") int maxRecipients,
            @Value("${app.stream.max-pending-per-subscriber:200}") int maxPendingPerSubscriber) {
        return new InMemoryNotificationStream(bufferPerRecipient, maxRecipients, maxPendingPerSubscriber);
    }

    /**
     * 팬아웃 코어. {@link NotificationDispatcher} 는 {@link AutoCloseable} 이라 스프링이 빈 소멸 시
     * 가상 스레드 executor 를 닫는다(추론된 destroyMethod).
     */
    @Bean
    public NotificationDispatcher notificationDispatcher(
            List<NotificationChannel> channels,
            DedupeStore notificationDedupeStore,
            @Value("${app.dispatch.per-channel-timeout-ms:3000}") long timeoutMs,
            @Value("${app.dispatch.max-attempts:3}") int maxAttempts,
            @Value("${app.dispatch.base-backoff-ms:50}") long backoffMs) {
        return new NotificationDispatcher(channels, notificationDedupeStore, timeoutMs, maxAttempts, backoffMs);
    }
}
