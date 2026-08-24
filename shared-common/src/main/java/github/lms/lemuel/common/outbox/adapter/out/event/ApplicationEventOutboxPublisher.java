package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 폴백 구현: Spring ApplicationEventPublisher 로 outbox 이벤트를 in-process 전달.
 *
 * <p>{@code app.kafka.enabled=false} (기본) 일 때만 활성. Kafka 가 활성화되면
 * {@link KafkaOutboxPublisher} 가 이 빈 대신 등록된다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class ApplicationEventOutboxPublisher implements PublishExternalEventPort {

    private final ApplicationEventPublisher publisher;

    public ApplicationEventOutboxPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OutboxEvent event) {
        publisher.publishEvent(event);
    }
}
