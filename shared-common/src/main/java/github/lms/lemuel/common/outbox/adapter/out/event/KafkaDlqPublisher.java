package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 재시도 한계 초과로 FAILED 가 된 outbox 이벤트를 Dead Letter 토픽으로 발행한다.
 *
 * <p>토픽 명: {@code lemuel.dlq.<aggregate_type>.<event_suffix>}
 *   예) Payment / PaymentCaptured → lemuel.dlq.payment.captured
 *
 * <p>헤더에 원인 정보 (event_id, retry_count, last_error) 를 함께 실어
 * DLQ 컨슈머 (알람·티켓 자동화) 가 메시지 본문 파싱 없이 메타정보로 라우팅 가능.
 *
 * <p>활성화 조건: {@code app.kafka.enabled=true} — Kafka 비활성 환경은 NoOp 으로 폴백.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaDlqPublisher implements PublishDlqEventPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaDlqPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaDlqPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishToDlq(OutboxEvent event) {
        String topic = resolveDlqTopic(event);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic,
                null,
                event.getAggregateId(),
                event.getPayload()
        );
        record.headers().add(new RecordHeader("event_id",
                event.getEventId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event_type",
                event.getEventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("retry_count",
                Integer.toString(event.getRetryCount()).getBytes(StandardCharsets.UTF_8)));
        if (event.getLastError() != null) {
            record.headers().add(new RecordHeader("last_error",
                    event.getLastError().getBytes(StandardCharsets.UTF_8)));
        }
        if (event.getTraceParent() != null && !event.getTraceParent().isBlank()) {
            record.headers().add(new RecordHeader("traceparent",
                    event.getTraceParent().getBytes(StandardCharsets.UTF_8)));
        }

        try {
            kafkaTemplate.send(record);
            log.warn("Outbox event published to DLQ. topic={}, eventId={}, retryCount={}",
                    topic, event.getEventId(), event.getRetryCount());
        } catch (Exception e) {
            // DLQ 발행 자체가 실패하더라도 원본 outbox 는 이미 FAILED 상태로 보존됨 — 운영자가 콘솔에서 발견 가능
            log.error("DLQ publish failed for eventId={}. 원본 outbox FAILED 레코드는 보존됨.",
                    event.getEventId(), e);
        }
    }

    private static String resolveDlqTopic(OutboxEvent event) {
        String aggregate = event.getAggregateType().toLowerCase(Locale.ROOT);
        String eventType = event.getEventType();
        if (eventType.startsWith(event.getAggregateType())) {
            eventType = eventType.substring(event.getAggregateType().length());
        }
        return "lemuel.dlq." + aggregate + "." + camelToSnake(eventType);
    }

    private static String camelToSnake(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
