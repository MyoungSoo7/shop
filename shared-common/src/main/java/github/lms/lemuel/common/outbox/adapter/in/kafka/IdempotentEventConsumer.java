package github.lms.lemuel.common.outbox.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 멱등 Kafka 이벤트 컨슈머의 공통 골격(Template Method).
 *
 * <p>모든 이벤트 컨슈머가 반복하던 다음 흐름을 한 곳에 고정한다 —
 * {@code event_id} 헤더 추출 → {@code processed_events(consumer_group, event_id)} 멱등 체크 →
 * JSON 파싱(실패 시 예외 → DLT) → {@link #handle} 위임 → 멱등 마커 저장 → ack.
 * 서브클래스는 {@link #consumerGroup()} / {@link #eventType()} / {@link #handle} 세 훅만 채운다.
 *
 * <p>3단 멱등 방어의 2단계(processed_events PK)를 구조적으로 강제해, 새 컨슈머를 추가할 때
 * 멱등 preamble 을 빠뜨리는 실수를 원천 차단한다. 서브클래스는 자신의
 * {@code @KafkaListener}/{@code @Transactional} 메서드에서 {@link #consume}만 호출하면 된다
 * (리스너 토픽·그룹·트랜잭션 경계는 컨슈머별로 유지).
 *
 * <p><b>비대상:</b> practice-delay 토글·순서역전 처리 등 컨슈머 고유 로직이 큰 경우는
 * {@link #handle} 안에서 처리하거나 이 골격을 상속하지 않는다(억지로 끼워 맞추지 않는다).
 */
public abstract class IdempotentEventConsumer {

    /** 서브클래스 이름으로 로거를 생성해 로그 출처를 원래 컨슈머와 동일하게 유지한다. */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    /** 옵트인 격리 훅 — null 이면 레거시 동작(경고 로그 후 유실)이 정확히 유지된다. */
    private final ConsumedEventQuarantine quarantine;

    protected IdempotentEventConsumer(ProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper) {
        this(processedEventRepository, objectMapper, null);
    }

    /** 격리 추적 옵트인 생성자 — {@link ConsumedEventQuarantine} 계약 참조. */
    protected IdempotentEventConsumer(ProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper,
                                      ConsumedEventQuarantine quarantine) {
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
        this.quarantine = quarantine;
    }

    /** 멱등 키·마커에 쓰이는 컨슈머 그룹 식별자. */
    protected abstract String consumerGroup();

    /** {@code processed_events.event_type} 에 기록할 이벤트 명(관측/디버깅용). */
    protected abstract String eventType();

    /**
     * 실제 도메인 처리(프로젝션 upsert 또는 use case 호출)를 수행한다.
     * 멱등 체크를 통과하고 JSON 파싱에 성공한 뒤에만 호출된다.
     *
     * @param payload 파싱된 이벤트 본문
     * @param eventId 멱등 키(로그·검증 메시지에 사용)
     */
    protected abstract void handle(JsonNode payload, UUID eventId);

    /**
     * 멱등 마커 저장 직후·ack 직전에 호출되는 사후 훅. 기본 no-op.
     * 프로젝션 지연 메트릭 기록 등 "정상 처리 1건"에 종속된 부수효과에 사용한다.
     */
    protected void afterProcessed(ConsumerRecord<String, String> record) {
        // no-op by default
    }

    /**
     * 공통 멱등 처리 골격. 서브클래스의 {@code @KafkaListener} 메서드가 그대로 위임한다.
     */
    protected final void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ExtractedEventId extracted = extractEventId(record);
        if (extracted.eventId() == null) {
            // 격리 훅이 있으면 유실 대신 추적 기록을 남긴다 — 기록 실패 시 예외 전파(ack 안 함)가 무유실에 안전.
            if (quarantine != null) {
                quarantine.quarantine(consumerGroup(), extracted.cause(), extracted.causeDetail(), record, null);
            }
            log.warn("event_id 헤더 없는/불량 레코드 {}. topic={}, offset={}",
                    quarantine == null ? "스킵" : "격리", record.topic(), record.offset());
            ack.acknowledge();
            return;
        }
        UUID eventId = extracted.eventId();

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(consumerGroup(), eventId);
        if (processedEventRepository.existsById(key)) {
            if (quarantine != null) {
                quarantine.duplicate(consumerGroup(), eventId, record);
            }
            log.info("이미 처리된 이벤트 스킵. group={}, eventId={}", consumerGroup(), eventId);
            ack.acknowledge();
            return;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(record.value());
        } catch (JsonProcessingException e) {
            // 파싱 실패는 재시도로 복구 불가 → 격리 기록 후 예외를 던져 DLT 로도 보낸다(추적 계층 공존).
            if (quarantine != null) {
                quarantine.quarantine(consumerGroup(), ConsumedEventQuarantine.Cause.INVALID_PAYLOAD,
                        e.getMessage(), record, eventId);
            }
            log.error("잘못된 JSON payload (DLT 대상). eventId={}, payload={}", eventId, record.value());
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        try {
            handle(payload, eventId);
        } catch (IllegalArgumentException e) {
            // required() 계약 위반(필수 필드 누락·금액 형식 오류) — non-retryable → 격리 기록 후 DLT 공존.
            // 도메인은 타입 예외를 쓰므로(OO 게이트) 여기 IAE 는 payload 계약 위반으로 간주한다.
            if (quarantine != null) {
                quarantine.quarantine(consumerGroup(), ConsumedEventQuarantine.Cause.INVALID_PAYLOAD,
                        e.getMessage(), record, eventId);
            }
            throw e;
        }

        processedEventRepository.save(new ProcessedEventJpaEntity(consumerGroup(), eventId, eventType()));
        afterProcessed(record);
        ackAfterCommit(ack);
    }

    /**
     * 성공 경로 ack — 트랜잭션이 활성이면 <b>커밋 이후로</b> 미룬다.
     *
     * <p>{@code MANUAL_IMMEDIATE} 에서 {@code ack.acknowledge()} 는 오프셋을 즉시 커밋한다.
     * 리스너가 {@code @Transactional} 이면 도메인 변경·{@code processed_events} 저장은 아직
     * 커밋 전이므로, 오프셋만 먼저 확정된 뒤 DB 커밋이 실패하면 재전달 없이 조용히 유실된다.
     * afterCommit 에 ack 를 걸어 "DB 커밋 성공 → 오프셋 확정" 순서를 보장한다(롤백 시 ack 미실행 → 재전달).
     *
     * <p>트랜잭션이 없으면(비트랜잭션 컨슈머) 기존처럼 즉시 ack — 완전 하위호환.
     */
    private void ackAfterCommit(Acknowledgment ack) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ack.acknowledge();
                }
            });
        } else {
            ack.acknowledge();
        }
    }

    /**
     * 필수 필드 접근 헬퍼 — 누락/null 은 재시도로 복구 불가한 계약 위반이므로
     * {@link IllegalArgumentException}(에러핸들러 non-retryable)을 던져 즉시 DLT 로 격리한다.
     * 무검증 {@code node.get(...).asText()} 는 NPE 로 새어 재시도를 낭비하므로 이 헬퍼를 쓴다.
     */
    protected static JsonNode required(JsonNode payload, String field, UUID eventId) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("필수 필드 누락: " + field + ", eventId=" + eventId);
        }
        return value;
    }

    protected static String requiredText(JsonNode payload, String field, UUID eventId) {
        return required(payload, field, eventId).asText();
    }

    protected static long requiredLong(JsonNode payload, String field, UUID eventId) {
        return required(payload, field, eventId).asLong();
    }

    /** 금액 필드 — 숫자가 아니면 {@link NumberFormatException}(IAE 하위) → 역시 즉시 DLT. */
    protected static BigDecimal requiredDecimal(JsonNode payload, String field, UUID eventId) {
        return new BigDecimal(required(payload, field, eventId).asText());
    }

    /** event_id 추출 결과 — 실패 시 원인 분류·원문을 보존해 격리 기록의 증거로 쓴다. */
    private record ExtractedEventId(UUID eventId, ConsumedEventQuarantine.Cause cause, String causeDetail) { }

    private static ExtractedEventId extractEventId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_id");
        if (header == null) {
            return new ExtractedEventId(null, ConsumedEventQuarantine.Cause.MISSING_EVENT_ID, null);
        }
        String raw = new String(header.value(), StandardCharsets.UTF_8);
        try {
            return new ExtractedEventId(UUID.fromString(raw), null, null);
        } catch (IllegalArgumentException e) {
            return new ExtractedEventId(null, ConsumedEventQuarantine.Cause.INVALID_EVENT_ID, raw);
        }
    }
}
