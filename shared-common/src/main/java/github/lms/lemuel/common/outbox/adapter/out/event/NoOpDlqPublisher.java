package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka 가 꺼져있는 로컬·테스트 환경의 폴백 DLQ 발행자.
 *
 * <p>실제 Kafka 토픽으로 발행하지 않고 ERROR 로그만 남긴다 — outbox FAILED 레코드는
 * DB 에 그대로 보존되므로 Admin REST API 로 재처리 가능.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpDlqPublisher implements PublishDlqEventPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpDlqPublisher.class);

    @Override
    public void publishToDlq(OutboxEvent event) {
        log.error("[DLQ-noop] outbox event hit retry limit. eventId={}, type={}, aggregate={}/{}, retries={}, lastError={}",
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getRetryCount(),
                event.getLastError());
    }
}
