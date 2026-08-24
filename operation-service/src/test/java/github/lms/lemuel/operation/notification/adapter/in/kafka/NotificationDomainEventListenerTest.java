package github.lms.lemuel.operation.notification.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.in.DispatchNotificationUseCase;
import github.lms.lemuel.operation.notification.domain.Notification;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 컨슈머의 실패 전파 계약.
 *
 * <p>배경: 이 리스너는 원래 모든 실패를 {@code catch (Exception) { log.error(...) }} 로 삼켰다.
 * 컨테이너 입장에서는 정상 처리로 보여 오프셋이 커밋되고 메시지는 사라진다 — DLT 배선을 붙여도
 * 예외가 밖으로 나오지 않으면 재시도도 격리도 일어나지 않는다. "삼키지 않는다"가 DLQ 의 전제 조건이고,
 * 그 계약을 여기서 고정한다.
 *
 * <p>이관하며 추가된 축: <b>ack 타이밍</b>. operation-service 의 ack-mode 는
 * {@code MANUAL_IMMEDIATE} 라, 실패 경로에서 ack 가 나가 버리면 에러 핸들러가 손도 대기 전에
 * 오프셋이 커밋된다(이관 전 Kotlin 모듈은 RECORD 모드라 이 축이 아예 없었다).
 */
class NotificationDomainEventListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** ack 호출 여부를 관찰하는 fake. */
    private static final class RecordingAck implements Acknowledgment {
        private final AtomicInteger acks = new AtomicInteger();

        @Override
        public void acknowledge() {
            acks.incrementAndGet();
        }

        int acks() {
            return acks.get();
        }
    }

    private static final class RecordingDispatcher implements DispatchNotificationUseCase {
        private final RuntimeException failure;
        private final DispatchResult result;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Notification last;

        RecordingDispatcher() {
            this(null, new DispatchResult(false, List.of()));
        }

        RecordingDispatcher(RuntimeException failure, DispatchResult result) {
            this.failure = failure;
            this.result = result;
        }

        static RecordingDispatcher failing(RuntimeException failure) {
            return new RecordingDispatcher(failure, null);
        }

        static RecordingDispatcher returning(DispatchResult result) {
            return new RecordingDispatcher(null, result);
        }

        @Override
        public DispatchResult dispatch(Notification notification) {
            calls.incrementAndGet();
            last = notification;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        int calls() {
            return calls.get();
        }

        Notification last() {
            return last;
        }
    }

    private static ConsumerRecord<String, String> record(String payload, String eventIdHeader) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.settlement.confirmed", 0, 0L, "SET-1", payload);
        if (eventIdHeader != null) {
            record.headers().add(new RecordHeader("event_id", eventIdHeader.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private RecordingAck deliver(DispatchNotificationUseCase dispatcher, String payload) {
        return deliver(dispatcher, payload, "11111111-1111-1111-1111-111111111111");
    }

    private RecordingAck deliver(DispatchNotificationUseCase dispatcher, String payload, String eventIdHeader) {
        RecordingAck ack = new RecordingAck();
        new NotificationDomainEventListener(dispatcher, objectMapper).onEvent(record(payload, eventIdHeader), ack);
        return ack;
    }

    @Test
    @DisplayName("정상 이벤트는 디스패치되고 ack 된다")
    void dispatchesValidEventAndAcks() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();

        RecordingAck ack = assertDoesNotThrow(
                () -> deliver(dispatcher, "{\"sellerId\":\"S1\",\"settlementId\":\"SET-1\"}"));

        assertEquals(1, dispatcher.calls());
        assertEquals(1, ack.acks(), "성공 경로는 정확히 한 번 ack 한다");
    }

    @Test
    @DisplayName("event_id 헤더가 멱등 키가 된다 — kafka 키가 아니라")
    void eventIdComesFromTheHeaderNotTheKafkaKey() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();

        deliver(dispatcher, "{\"sellerId\":\"S1\",\"settlementId\":\"SET-1\"}", "evt-from-header");

        assertNotNull(dispatcher.last());
        assertEquals("evt-from-header", dispatcher.last().eventId(),
                "kafka 키(SET-1)는 애그리거트라 같은 애그리거트의 두 번째 이벤트를 삼킨다");
    }

    @Test
    @DisplayName("헤더가 없으면 페이로드 eventId, 그것도 없으면 kafka 키로 폴백한다")
    void fallsBackToPayloadThenKey() {
        RecordingDispatcher fromPayload = new RecordingDispatcher();
        deliver(fromPayload, "{\"sellerId\":\"S1\",\"eventId\":\"evt-payload\"}", null);
        assertEquals("evt-payload", fromPayload.last().eventId());

        RecordingDispatcher fromKey = new RecordingDispatcher();
        deliver(fromKey, "{\"sellerId\":\"S1\"}", null);
        assertEquals("SET-1", fromKey.last().eventId());
    }

    @Test
    @DisplayName("디스패치 실패는 삼키지 않고 던진다 — 에러 핸들러가 재시도·DLT 를 결정해야 한다")
    void propagatesDispatchFailure() {
        RecordingDispatcher dispatcher = RecordingDispatcher.failing(new IllegalStateException("channel down"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> deliver(dispatcher, "{\"sellerId\":\"S1\"}"));

        assertEquals("channel down", thrown.getMessage());
    }

    @Test
    @DisplayName("실패 경로에서는 ack 하지 않는다 — ack 하면 에러 핸들러 전에 오프셋이 커밋된다")
    void doesNotAckOnFailure() {
        RecordingDispatcher dispatcher = RecordingDispatcher.failing(new IllegalStateException("channel down"));
        RecordingAck ack = new RecordingAck();

        assertThrows(IllegalStateException.class, () ->
                new NotificationDomainEventListener(dispatcher, objectMapper)
                        .onEvent(record("{\"sellerId\":\"S1\"}", "evt-1"), ack));

        assertEquals(0, ack.acks(), "MANUAL_IMMEDIATE 에서 실패 시 ack 는 곧 유실이다");
    }

    @Test
    @DisplayName("모든 채널이 실패하면 던진다 — 아무 데도 전달되지 않은 알림이 조용히 커밋되면 유실이다")
    void propagatesAllChannelsFailed() {
        RecordingDispatcher dispatcher = RecordingDispatcher.returning(new DispatchResult(false, List.of(
                new ChannelResult.Failure("email", 3, "smtp down"),
                new ChannelResult.Failure("sse", 3, "no subscriber"))));

        NotificationDispatchFailedException thrown = assertThrows(NotificationDispatchFailedException.class,
                () -> deliver(dispatcher, "{\"sellerId\":\"S1\",\"settlementId\":\"SET-1\"}"));

        // 즉시-DLT 분류(IllegalStateException 계열)여야 한다. 채널은 이미 자체 재시도를 소진했고,
        // dedupe 가 eventId 를 선점한 뒤라 Kafka 재배달은 무의미한 no-op 이 된다.
        assertInstanceOf(IllegalStateException.class, thrown);
    }

    @Test
    @DisplayName("한 채널이라도 성공하면 던지지 않는다 — 이미 전달된 알림을 DLT 로 보내면 재발송 중복이 된다")
    void doesNotThrowOnPartialSuccess() {
        RecordingDispatcher dispatcher = RecordingDispatcher.returning(new DispatchResult(false, List.of(
                new ChannelResult.Success("sse", 1),
                new ChannelResult.Failure("email", 3, "smtp down"))));

        RecordingAck ack = assertDoesNotThrow(
                () -> deliver(dispatcher, "{\"sellerId\":\"S1\",\"settlementId\":\"SET-1\"}"));

        assertEquals(1, ack.acks());
    }

    @Test
    @DisplayName("중복으로 스킵된 이벤트는 실패가 아니다 — 멱등 스킵을 DLT 로 보내면 안 된다")
    void doesNotThrowOnDedupedSkip() {
        RecordingDispatcher dispatcher = RecordingDispatcher.returning(DispatchResult.skipped());

        RecordingAck ack = assertDoesNotThrow(
                () -> deliver(dispatcher, "{\"sellerId\":\"S1\",\"settlementId\":\"SET-1\"}"));

        assertEquals(1, ack.acks());
    }

    @Test
    @DisplayName("활성 채널 0개(빈 결과)는 배포 설정 오류지 메시지 문제가 아니다 — DLT 로 보내지 않는다")
    void doesNotThrowWhenNoChannelsWereEnabled() {
        RecordingDispatcher dispatcher = RecordingDispatcher.returning(new DispatchResult(false, List.of()));

        RecordingAck ack = assertDoesNotThrow(
                () -> deliver(dispatcher, "{\"sellerId\":\"S1\",\"settlementId\":\"SET-1\"}"));

        assertEquals(1, ack.acks());
    }

    @Test
    @DisplayName("파싱 불가 페이로드는 조용히 스킵하지 않고 던진다 — 계약 드리프트를 DLT 에 보존한다")
    void propagatesUnparseablePayload() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingAck ack = new RecordingAck();

        assertThrows(UnparseableEventPayloadException.class, () ->
                new NotificationDomainEventListener(dispatcher, objectMapper)
                        .onEvent(record("not-json-at-all", "evt-1"), ack));

        // 파싱이 깨졌으면 폴백 수신자에게 엉뚱한 GENERIC 알림을 만들어 보내지 않는다(원래 의도 보존).
        assertEquals(0, dispatcher.calls());
        assertEquals(0, ack.acks());
    }

    @Test
    @DisplayName("파싱 실패 예외는 재시도 불가(IAE) 분류에 걸린다")
    void unparseablePayloadIsClassifiedAsNonRetryable() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();

        UnparseableEventPayloadException thrown = assertThrows(UnparseableEventPayloadException.class,
                () -> deliver(dispatcher, "{broken"));

        assertInstanceOf(IllegalArgumentException.class, thrown,
                "같은 바이트를 다시 파싱해도 결과가 같으므로 재시도가 아니라 즉시 격리여야 한다");
    }
}
