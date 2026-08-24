package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 로 outbox 이벤트를 발행하는 {@link PublishExternalEventPort} 구현체.
 *
 * <p>활성화 조건: {@code app.kafka.enabled=true}
 *
 * <p>토픽 명명 규칙: {@code lemuel.<aggregate_type_lower>.<event_type_snake>}
 *   예) aggregateType=Payment, eventType=PaymentCaptured → lemuel.payment.captured
 *
 * <p>파티셔닝: aggregateId (예: payment_id) 를 key 로 사용해 같은 집합의 이벤트 순서 보장.
 *
 * <p>동기 send + get(timeout): 폴러 트랜잭션 안에서 실패를 즉시 감지해 markFailed 경로로 간다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaOutboxPublisher implements PublishExternalEventPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private static final long SEND_TIMEOUT_SEC = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            SendResult<String, String> result = kafkaTemplate.send(buildRecord(event))
                    .get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);
            log.debug("Kafka publish OK: topic={}, partition={}, offset={}, eventId={}",
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    event.getEventId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka publish interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Kafka publish failed for eventId=" + event.getEventId(), e);
        }
    }

    /**
     * 비동기 발행 — {@code .get()} 없이 send future 를 그대로 반환한다. 배치 폴러가 여러 이벤트를
     * 한꺼번에 dispatch 한 뒤 future 들을 모아 기다리면, 프로듀서가 in-flight 로 묶어 보내 라운드트립이
     * 병렬화된다. (기존 동기 {@link #publish} 의 N회 직렬 {@code .get()} 대비 처리량↑)
     */
    @Override
    public CompletableFuture<Void> publishAsync(OutboxEvent event) {
        return kafkaTemplate.send(buildRecord(event)).thenAccept(result ->
                log.debug("Kafka publish OK (async): topic={}, partition={}, offset={}, eventId={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getEventId()));
    }

    private ProducerRecord<String, String> buildRecord(OutboxEvent event) {
        String topic = resolveTopic(event);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic,
                null, // partition — 키 해시로 자동 할당
                event.getAggregateId(),
                event.getPayload()
        );
        record.headers().add(new RecordHeader("event_id",
                event.getEventId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event_type",
                event.getEventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("aggregate_type",
                event.getAggregateType().getBytes(StandardCharsets.UTF_8)));
        // N4 봉투 — 사건 시각(UTC ISO-8601)·스키마 버전·발행자. 페이로드가 아닌 헤더에 싣는 것은
        // event_id/traceparent 와 같은 결정: 봉투는 전송 메타, 페이로드는 도메인 본문.
        record.headers().add(new RecordHeader("occurred_at",
                event.getOccurredAt().toInstant().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event_version",
                String.valueOf(event.getEventVersion()).getBytes(StandardCharsets.UTF_8)));
        if (event.getProducer() != null && !event.getProducer().isBlank()) {
            record.headers().add(new RecordHeader("producer",
                    event.getProducer().getBytes(StandardCharsets.UTF_8)));
        }
        // 도메인 트랜잭션 시점의 W3C trace context 를 헤더로 복원 → 컨슈머가 같은 trace 합류
        if (event.getTraceParent() != null && !event.getTraceParent().isBlank()) {
            record.headers().add(new RecordHeader("traceparent",
                    event.getTraceParent().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    /**
     * "Payment" + "PaymentCaptured" → "lemuel.payment.captured"
     * 접두사 중복을 피하기 위해 eventType 에서 aggregateType prefix 를 제거한 뒤 camel→snake 변환.
     */
    private static String resolveTopic(OutboxEvent event) {
        String aggregate = event.getAggregateType().toLowerCase(Locale.ROOT);
        String eventType = event.getEventType();
        if (eventType.startsWith(event.getAggregateType())) {
            eventType = eventType.substring(event.getAggregateType().length());
        }
        String suffix = camelToSnake(eventType);
        return "lemuel." + aggregate + "." + suffix;
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
