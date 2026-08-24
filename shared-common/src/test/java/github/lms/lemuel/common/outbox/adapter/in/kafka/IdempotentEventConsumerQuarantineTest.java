package github.lms.lemuel.common.outbox.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * 격리 추적(옵트인) 계약 테스트 — seed-p0-3 AC1/AC2 의 소비측 절반.
 *
 * <p>핵심: event_id 누락·불량 UUID·파싱 실패·필수 필드 위반이 모두 "추적 가능한 결과"를
 * 남기는지, 그리고 훅 미주입(레거시 생성자) 시 기존 동작이 정확히 보존되는지.
 */
class IdempotentEventConsumerQuarantineTest {

    private static final String GROUP = "test-group";

    /** 캡처형 테스트 더블 — 격리·중복 호출을 기록한다. */
    static class RecordingQuarantine implements ConsumedEventQuarantine {
        record Entry(String group, Cause cause, String detail, String topic, long offset, UUID eventId) { }
        final List<Entry> quarantined = new ArrayList<>();
        final List<UUID> duplicates = new ArrayList<>();

        @Override
        public void quarantine(String consumerGroup, Cause cause, String causeDetail,
                               ConsumerRecord<String, String> record, UUID eventId) {
            quarantined.add(new Entry(consumerGroup, cause, causeDetail, record.topic(), record.offset(), eventId));
        }

        @Override
        public void duplicate(String consumerGroup, UUID eventId, ConsumerRecord<String, String> record) {
            duplicates.add(eventId);
        }
    }

    /** handle 호출을 기록하는 최소 컨슈머 — required() 계약 위반 케이스도 재현한다. */
    static class TestConsumer extends IdempotentEventConsumer {
        final List<UUID> handled = new ArrayList<>();
        final boolean requireAmountField;

        TestConsumer(ProcessedEventRepository repo, ConsumedEventQuarantine quarantine, boolean requireAmountField) {
            super(repo, new ObjectMapper(), quarantine);
            this.requireAmountField = requireAmountField;
        }

        TestConsumer(ProcessedEventRepository repo) { // 레거시 생성자 경로
            super(repo, new ObjectMapper());
            this.requireAmountField = false;
        }

        @Override protected String consumerGroup() { return GROUP; }
        @Override protected String eventType() { return "TestEvent"; }

        @Override
        protected void handle(JsonNode payload, UUID eventId) {
            if (requireAmountField) requiredText(payload, "amount", eventId);
            handled.add(eventId);
        }

        void run(ConsumerRecord<String, String> record, Acknowledgment ack) {
            consume(record, ack);
        }
    }

    private static ConsumerRecord<String, String> record(String payload, String eventIdHeader) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("t.topic", 0, 42L, "key", payload);
        if (eventIdHeader != null) {
            r.headers().add(new RecordHeader("event_id", eventIdHeader.getBytes(StandardCharsets.UTF_8)));
        }
        return r;
    }

    private static ProcessedEventRepository freshRepo() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        when(repo.existsById(any())).thenReturn(false);
        return repo;
    }

    @Test
    @DisplayName("event_id 헤더 누락 → MISSING_EVENT_ID 격리 기록 + ack (유실 0)")
    void missingEventIdIsQuarantinedAndAcked() {
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(freshRepo(), q, false);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.run(record("{\"a\":1}", null), ack);

        assertThat(q.quarantined).singleElement().satisfies(e -> {
            assertThat(e.cause()).isEqualTo(ConsumedEventQuarantine.Cause.MISSING_EVENT_ID);
            assertThat(e.group()).isEqualTo(GROUP);
            assertThat(e.topic()).isEqualTo("t.topic");
            assertThat(e.eventId()).isNull();
        });
        verify(ack).acknowledge();
        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("event_id 가 UUID 불가 문자열 → INVALID_EVENT_ID 격리(원문 보존) + ack")
    void invalidEventIdIsQuarantinedWithRawValue() {
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(freshRepo(), q, false);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.run(record("{\"a\":1}", "not-a-uuid"), ack);

        assertThat(q.quarantined).singleElement().satisfies(e -> {
            assertThat(e.cause()).isEqualTo(ConsumedEventQuarantine.Cause.INVALID_EVENT_ID);
            assertThat(e.detail()).contains("not-a-uuid");
            assertThat(e.eventId()).isNull();
        });
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 → INVALID_PAYLOAD 격리 기록 후 예외 rethrow (DLT 공존, ack 없음)")
    void malformedPayloadIsQuarantinedThenRethrown() {
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(freshRepo(), q, false);
        Acknowledgment ack = mock(Acknowledgment.class);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.run(record("not-json", eventId.toString()), ack))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(q.quarantined).singleElement().satisfies(e -> {
            assertThat(e.cause()).isEqualTo(ConsumedEventQuarantine.Cause.INVALID_PAYLOAD);
            assertThat(e.eventId()).isEqualTo(eventId);
        });
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("필수 필드 누락(required 계약 위반) → INVALID_PAYLOAD 격리 기록 후 rethrow")
    void requiredFieldViolationIsQuarantinedThenRethrown() {
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(freshRepo(), q, true);
        Acknowledgment ack = mock(Acknowledgment.class);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.run(record("{\"other\":1}", eventId.toString()), ack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");

        assertThat(q.quarantined).singleElement().satisfies(e ->
                assertThat(e.cause()).isEqualTo(ConsumedEventQuarantine.Cause.INVALID_PAYLOAD));
        verify(ack, never()).acknowledge();
        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("이미 처리된 event_id 재도착 → duplicate 추적 + ack, handle 미호출")
    void duplicateArrivalIsTracked() {
        ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
        when(repo.existsById(any())).thenReturn(true);
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(repo, q, false);
        Acknowledgment ack = mock(Acknowledgment.class);
        UUID eventId = UUID.randomUUID();

        consumer.run(record("{\"a\":1}", eventId.toString()), ack);

        assertThat(q.duplicates).containsExactly(eventId);
        assertThat(q.quarantined).isEmpty();
        verify(ack).acknowledge();
        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("레거시 생성자(훅 미주입) — 누락 event_id 는 기존대로 경고 후 ack, 예외 없음 (하위 호환)")
    void legacyConstructorPreservesOldBehavior() {
        TestConsumer consumer = new TestConsumer(freshRepo());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.run(record("{\"a\":1}", null), ack);

        verify(ack).acknowledge();
        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("격리 기록 자체가 실패하면 예외 전파 + ack 없음 — 기록 실패가 유실로 이어지지 않는다")
    void quarantineFailurePropagatesWithoutAck() {
        ConsumedEventQuarantine failing = (group, cause, detail, rec, id) -> {
            throw new IllegalStateException("quarantine store down");
        };
        TestConsumer consumer = new TestConsumer(freshRepo(), failing, false);
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThatThrownBy(() -> consumer.run(record("{\"a\":1}", null), ack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarantine store down");
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("트랜잭션 활성 시 성공 ack 는 afterCommit 까지 미뤄진다 — 오프셋이 DB 커밋보다 먼저 확정되지 않는다")
    void successAckIsDeferredUntilAfterCommitWhenTransactional() {
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(freshRepo(), q, false);
        Acknowledgment ack = mock(Acknowledgment.class);
        UUID eventId = UUID.randomUUID();

        TransactionSynchronizationManager.initSynchronization();
        try {
            consumer.run(record("{\"a\":1}", eventId.toString()), ack);
            // 동기화(=트랜잭션) 활성 → 아직 커밋 전이므로 ack 는 미실행 (구현 전이면 여기서 즉시 ack → RED)
            verify(ack, never()).acknowledge();

            // 커밋 시점 재현 — 등록된 동기화의 afterCommit 발화 후에야 ack
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(TransactionSynchronization::afterCommit);
            verify(ack).acknowledge();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 없으면 성공 ack 는 즉시 실행 — 비트랜잭션 컨슈머 하위호환")
    void successAckIsImmediateWhenNoTransaction() {
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(freshRepo(), q, false);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.run(record("{\"a\":1}", UUID.randomUUID().toString()), ack);

        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("정상 이벤트는 훅 주입과 무관하게 handle 1회 + 멱등 마커 저장 + ack")
    void normalEventStillProcessedOnce() {
        ProcessedEventRepository repo = freshRepo();
        RecordingQuarantine q = new RecordingQuarantine();
        TestConsumer consumer = new TestConsumer(repo, q, false);
        Acknowledgment ack = mock(Acknowledgment.class);
        UUID eventId = UUID.randomUUID();

        consumer.run(record("{\"a\":1}", eventId.toString()), ack);

        assertThat(consumer.handled).containsExactly(eventId);
        assertThat(q.quarantined).isEmpty();
        assertThat(q.duplicates).isEmpty();
        verify(repo).save(any(ProcessedEventJpaEntity.class));
        verify(ack).acknowledge();
    }
}
