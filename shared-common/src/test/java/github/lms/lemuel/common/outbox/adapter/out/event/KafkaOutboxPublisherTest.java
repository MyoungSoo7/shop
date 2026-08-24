package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 파티션 키 = aggregateId 순서보장 가드 (ADR 0020 Phase 5.4).
 *
 * <p>같은 aggregate 의 이벤트는 항상 같은 Kafka key(=aggregateId)로 발행돼 같은 파티션에 들어가야
 * per-aggregate 순서가 보장된다. 이 불변식을 리팩터가 조용히 깨지 않도록 고정한다.
 */
class KafkaOutboxPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate);

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturePublish(OutboxEvent event) {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        RecordMetadata md = new RecordMetadata(new TopicPartition("t", 0), 0L, 0, 0L, 0, 0);
        org.mockito.Mockito.reset(kafkaTemplate); // 호출당 격리 (헬퍼 다회 호출 시 누적 방지)
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, md)));
        publisher.publish(event);
        org.mockito.Mockito.verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static OutboxEvent event(String aggregateType, String aggregateId, String eventType) {
        return OutboxEvent.rehydrate(1L, aggregateType, aggregateId, eventType, UUID.randomUUID(),
                "{}", OutboxEventStatus.PENDING, 0, null, LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("Phase 5.4: ProducerRecord key = aggregateId, partition=null(키 해시 라우팅)")
    void recordKeyEqualsAggregateId() {
        ProducerRecord<String, String> rec = capturePublish(event("Payment", "7", "PaymentCaptured"));

        assertThat(rec.key()).isEqualTo("7");
        assertThat(rec.partition()).isNull();
    }

    @Test
    @DisplayName("Phase 5.4: 토픽 = lemuel.<aggregate>.<event_snake> (aggregateType prefix 제거)")
    void topicResolvedFromAggregateAndEventType() {
        assertThat(capturePublish(event("Payment", "7", "PaymentCaptured")).topic())
                .isEqualTo("lemuel.payment.captured");
        assertThat(capturePublish(event("Product", "42", "ProductChanged")).topic())
                .isEqualTo("lemuel.product.changed");
        assertThat(capturePublish(event("User", "9", "UserRegistered")).topic())
                .isEqualTo("lemuel.user.registered");
    }

    @Test
    @DisplayName("Phase 5.4: event_id/event_type/aggregate_type 헤더가 실린다")
    void carriesIdentityHeaders() {
        ProducerRecord<String, String> rec = capturePublish(event("Order", "100", "OrderCreated"));

        assertThat(header(rec, "event_type")).isEqualTo("OrderCreated");
        assertThat(header(rec, "aggregate_type")).isEqualTo("Order");
        assertThat(header(rec, "event_id")).isNotBlank();
    }

    @Test
    @DisplayName("N4: occurred_at(UTC ISO-8601)·event_version·producer 봉투 헤더가 실린다")
    void carriesEnvelopeHeaders() {
        OutboxEvent e = OutboxEvent.pending("Order", "100", "OrderCreated", "{}")
                .withProducer("lemuel-order");

        ProducerRecord<String, String> rec = capturePublish(e);

        // Instant.toString() 은 항상 UTC('Z') — 소비측이 타임존 추정 없이 파싱할 수 있어야 한다
        assertThat(header(rec, "occurred_at")).endsWith("Z");
        assertThat(java.time.Instant.parse(header(rec, "occurred_at")))
                .isEqualTo(e.getOccurredAt().toInstant());
        assertThat(header(rec, "event_version")).isEqualTo("1");
        assertThat(header(rec, "producer")).isEqualTo("lemuel-order");
    }

    @Test
    @DisplayName("N4: producer 가 없으면 헤더를 아예 싣지 않는다(빈 문자열 금지)")
    void omitsProducerHeaderWhenUnknown() {
        ProducerRecord<String, String> rec = capturePublish(event("Order", "100", "OrderCreated"));

        assertThat(header(rec, "producer")).isNull();
    }

    private static String header(ProducerRecord<String, String> rec, String name) {
        var h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
