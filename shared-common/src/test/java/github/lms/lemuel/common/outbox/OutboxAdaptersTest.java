package github.lms.lemuel.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.common.outbox.adapter.in.web.OutboxAdminController;
import github.lms.lemuel.common.outbox.adapter.out.event.ApplicationEventOutboxPublisher;
import github.lms.lemuel.common.outbox.adapter.out.event.KafkaDlqPublisher;
import github.lms.lemuel.common.outbox.adapter.out.event.NoOpDlqPublisher;
import github.lms.lemuel.common.outbox.application.port.out.ClaimOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.in.OutboxAdminUseCase;
import github.lms.lemuel.common.outbox.application.service.OutboxBatchEventPublisher;
import github.lms.lemuel.common.outbox.application.service.OutboxPublisherScheduler;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * outbox 어댑터/서비스 단위 검증 — DLQ 발행자(Kafka/NoOp), 멀티워커 스케줄러,
 * 멱등 컨슈머 골격(Template Method), DLQ Admin REST, ApplicationEvent 폴백 발행자.
 */
class OutboxAdaptersTest {

    private static void invokeRegisterMetrics(OutboxPublisherScheduler scheduler) {
        try {
            var m = OutboxPublisherScheduler.class.getDeclaredMethod("registerMetrics");
            m.setAccessible(true);
            m.invoke(scheduler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static OutboxEvent event(String aggType, String aggId, String eventType,
                                     OutboxEventStatus status, int retry, String lastError) {
        return OutboxEvent.rehydrate(1L, aggType, aggId, eventType, UUID.randomUUID(), "{\"x\":1}",
                status, retry, lastError, LocalDateTime.now(), null, "00-trace-span-01");
    }

    // ─── KafkaDlqPublisher ───────────────────────────────────────────────────

    @Test
    @DisplayName("DLQ 토픽 = lemuel.dlq.<aggregate>.<event_snake>, 원인 헤더 부착")
    @SuppressWarnings("unchecked")
    void kafkaDlqTopicAndHeaders() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaDlqPublisher publisher = new KafkaDlqPublisher(template);
        OutboxEvent e = event("Payment", "7", "PaymentCaptured", OutboxEventStatus.FAILED, 10, "timeout");

        publisher.publishToDlq(e);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        ProducerRecord<String, String> rec = captor.getValue();
        assertThat(rec.topic()).isEqualTo("lemuel.dlq.payment.captured");
        assertThat(rec.key()).isEqualTo("7");
        assertThat(header(rec, "event_type")).isEqualTo("PaymentCaptured");
        assertThat(header(rec, "retry_count")).isEqualTo("10");
        assertThat(header(rec, "last_error")).isEqualTo("timeout");
        assertThat(header(rec, "traceparent")).isEqualTo("00-trace-span-01");
    }

    @Test
    @DisplayName("DLQ 발행 자체 실패는 삼켜진다(원본 FAILED 보존)")
    @SuppressWarnings("unchecked")
    void kafkaDlqSwallowsSendFailure() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenThrow(new RuntimeException("broker down"));
        KafkaDlqPublisher publisher = new KafkaDlqPublisher(template);

        // lastError=null, traceParent 없음 분기까지 커버
        OutboxEvent e = OutboxEvent.rehydrate(1L, "Order", "9", "OrderCreated", UUID.randomUUID(),
                "{}", OutboxEventStatus.FAILED, 10, null, LocalDateTime.now(), null, null);
        publisher.publishToDlq(e); // 예외 안 던짐
    }

    @Test
    @DisplayName("NoOpDlqPublisher: 예외 없이 로그만 (발행 안 함)")
    void noOpDlq() {
        new NoOpDlqPublisher().publishToDlq(
                event("Settlement", "1", "SettlementFailed", OutboxEventStatus.FAILED, 10, "err"));
    }

    private static String header(ProducerRecord<String, String> rec, String name) {
        var h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // ─── OutboxPublisherScheduler ────────────────────────────────────────────

    @Test
    @DisplayName("claim 된 이벤트가 있으면 배치 발행, gauge 갱신")
    void schedulerPublishesClaimed() {
        ClaimOutboxEventPort claim = mock(ClaimOutboxEventPort.class);
        LoadOutboxEventPort load = mock(LoadOutboxEventPort.class);
        OutboxBatchEventPublisher batch = mock(OutboxBatchEventPublisher.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(load.countPending()).thenReturn(5L);
        when(load.countFailed()).thenReturn(2L);
        List<OutboxEvent> claimed = List.of(event("Payment", "1", "PaymentCaptured", OutboxEventStatus.PENDING, 0, null));
        when(claim.claimPending(any(Integer.class), any(Duration.class), any(String.class))).thenReturn(claimed);
        when(batch.publishBatch(claimed)).thenReturn(new OutboxBatchEventPublisher.PublishOutcome(1, 0));

        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(claim, load, batch, registry);
        invokeRegisterMetrics(scheduler);
        scheduler.publishPendingEvents();

        verify(batch).publishBatch(claimed);
        assertThat(registry.get("outbox.pending.count").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("outbox.failed.count").gauge().value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("claim 이 비어 있으면 배치 발행을 호출하지 않는다")
    void schedulerSkipsWhenNoClaim() {
        ClaimOutboxEventPort claim = mock(ClaimOutboxEventPort.class);
        LoadOutboxEventPort load = mock(LoadOutboxEventPort.class);
        OutboxBatchEventPublisher batch = mock(OutboxBatchEventPublisher.class);
        when(load.countPending()).thenReturn(0L);
        when(load.countFailed()).thenReturn(0L);
        when(claim.claimPending(any(Integer.class), any(Duration.class), any(String.class))).thenReturn(List.of());

        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(claim, load, batch, new SimpleMeterRegistry());
        scheduler.publishPendingEvents();

        verify(batch, never()).publishBatch(any());
    }

    // ─── IdempotentEventConsumer ─────────────────────────────────────────────

    static class TestConsumer extends IdempotentEventConsumer {
        JsonNode handled;
        int afterCount;
        TestConsumer(ProcessedEventRepository repo, ObjectMapper om) { super(repo, om); }
        @Override protected String consumerGroup() { return "test-group"; }
        @Override protected String eventType() { return "TestEvent"; }
        @Override protected void handle(JsonNode payload, UUID eventId) { this.handled = payload; }
        @Override protected void afterProcessed(ConsumerRecord<String, String> record) { afterCount++; }
        void call(ConsumerRecord<String, String> r, Acknowledgment a) { consume(r, a); }
    }

    private static ConsumerRecord<String, String> record(String value, UUID eventId) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("topic", 0, 0L, "key", value);
        if (eventId != null) {
            r.headers().add(new RecordHeader("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return r;
    }

    @Test
    @DisplayName("정상 흐름: 멱등 통과 → handle → 마커 저장 → afterProcessed → ack")
    void consumerHappyPath() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        when(repo.existsById(any())).thenReturn(false);
        TestConsumer consumer = new TestConsumer(repo, new ObjectMapper());
        Acknowledgment ack = mock(Acknowledgment.class);
        UUID id = UUID.randomUUID();

        consumer.call(record("{\"a\":1}", id), ack);

        assertThat(consumer.handled.get("a").asInt()).isEqualTo(1);
        assertThat(consumer.afterCount).isEqualTo(1);
        verify(repo).save(any(ProcessedEventJpaEntity.class));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("event_id 헤더 없으면 스킵 + ack")
    void consumerSkipsWithoutEventId() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        TestConsumer consumer = new TestConsumer(repo, new ObjectMapper());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.call(record("{}", null), ack);

        verify(repo, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("이미 처리된 이벤트면 handle 없이 ack")
    void consumerIdempotentSkip() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        when(repo.existsById(any())).thenReturn(true);
        TestConsumer consumer = new TestConsumer(repo, new ObjectMapper());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.call(record("{}", UUID.randomUUID()), ack);

        assertThat(consumer.handled).isNull();
        verify(repo, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("잘못된 JSON 은 IllegalArgumentException(DLT 유도)")
    void consumerInvalidJsonThrows() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        when(repo.existsById(any())).thenReturn(false);
        TestConsumer consumer = new TestConsumer(repo, new ObjectMapper());
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThatThrownBy(() -> consumer.call(record("not-json{", UUID.randomUUID()), ack))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("event_id 값이 잘못된 UUID 면 헤더 없음으로 취급해 스킵")
    void consumerBadUuidHeaderSkips() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        TestConsumer consumer = new TestConsumer(repo, new ObjectMapper());
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> r = new ConsumerRecord<>("topic", 0, 0L, "key", "{}");
        r.headers().add(new RecordHeader("event_id", "not-a-uuid".getBytes(StandardCharsets.UTF_8)));

        consumer.call(r, ack);

        verify(repo, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("ProcessedEventJpaEntity: 복합키 equals/hashCode/getter")
    void processedEventEntity() {
        UUID id = UUID.randomUUID();
        var e = new ProcessedEventJpaEntity("g", id, "T");
        assertThat(e.getEventType()).isEqualTo("T");
        assertThat(e.getProcessedAt()).isNotNull();
        assertThat(e.getId().getConsumerGroup()).isEqualTo("g");
        assertThat(e.getId().getEventId()).isEqualTo(id);

        var k1 = new ProcessedEventJpaEntity.ProcessedEventId("g", id);
        var k2 = new ProcessedEventJpaEntity.ProcessedEventId("g", id);
        var k3 = new ProcessedEventJpaEntity.ProcessedEventId("other", id);
        assertThat(k1).isEqualTo(k2).hasSameHashCodeAs(k2);
        assertThat(k1).isNotEqualTo(k3).isNotEqualTo(null).isNotEqualTo("x");
        assertThat(k1).isEqualTo(k1);
    }

    // ─── OutboxAdminController ───────────────────────────────────────────────

    @Test
    @DisplayName("listDlq: 총건수 + 항목 매핑")
    void adminListDlq() {
        OutboxAdminUseCase useCase = mock(OutboxAdminUseCase.class);
        OutboxEvent e = event("Payment", "7", "PaymentCaptured", OutboxEventStatus.FAILED, 10, "err");
        when(useCase.listFailed(0, 20)).thenReturn(List.of(e));
        when(useCase.failedCount()).thenReturn(1L);
        OutboxAdminController controller = new OutboxAdminController(useCase);

        ResponseEntity<OutboxAdminController.DlqPageResponse> res = controller.listDlq(0, 20);

        assertThat(res.getBody().totalFailed()).isEqualTo(1L);
        assertThat(res.getBody().items()).hasSize(1);
        assertThat(res.getBody().items().get(0).event()).containsEntry("eventType", "PaymentCaptured");
        assertThat(res.getBody().items().get(0).createdAt()).isNotNull();
    }

    @Test
    @DisplayName("retry: usecase 위임 후 항목 반환")
    void adminRetry() {
        OutboxAdminUseCase useCase = mock(OutboxAdminUseCase.class);
        UUID id = UUID.randomUUID();
        OutboxEvent e = event("Order", "1", "OrderCreated", OutboxEventStatus.PENDING, 0, null);
        when(useCase.retry(id)).thenReturn(e);
        OutboxAdminController controller = new OutboxAdminController(useCase);

        ResponseEntity<OutboxAdminController.DlqItemResponse> res = controller.retry(id);
        assertThat(res.getBody().event()).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("skip: 인증 없으면 operatorId=anonymous 로 위임")
    void adminSkipAnonymous() {
        OutboxAdminUseCase useCase = mock(OutboxAdminUseCase.class);
        UUID id = UUID.randomUUID();
        OutboxEvent e = event("Order", "1", "OrderCreated", OutboxEventStatus.PUBLISHED, 0, "[SKIPPED] x");
        when(useCase.skip(any(), any(), any())).thenReturn(e);
        OutboxAdminController controller = new OutboxAdminController(useCase);

        ResponseEntity<OutboxAdminController.DlqItemResponse> res =
                controller.skip(id, new OutboxAdminController.SkipRequest("보정완료"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(useCase).skip(id, "보정완료", "anonymous");
    }

    // ─── ApplicationEventOutboxPublisher ─────────────────────────────────────

    @Test
    @DisplayName("ApplicationEvent 폴백 발행자: 스프링 이벤트로 전달")
    void applicationEventPublisher() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        ApplicationEventOutboxPublisher publisher = new ApplicationEventOutboxPublisher(spring);
        OutboxEvent e = event("Order", "1", "OrderCreated", OutboxEventStatus.PENDING, 0, null);

        publisher.publish(e);

        verify(spring).publishEvent(e);
    }
}
