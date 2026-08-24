package github.lms.lemuel.common.outbox.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Outbox 이벤트 도메인 모델 (순수 POJO).
 *
 * 비즈니스 변경과 동일한 트랜잭션 안에서 PENDING 상태로 기록되고,
 * OutboxPublisherScheduler 가 주기적으로 읽어 외부 시스템에 발행 후 PUBLISHED 로 전이시킨다.
 */
public class OutboxEvent {

    /** 봉투 스키마 버전 기본값 (N4). */
    public static final int DEFAULT_EVENT_VERSION = 1;

    private final Long id;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final UUID eventId;
    private final String payload;
    private OutboxEventStatus status;
    private int retryCount;
    private String lastError;
    private final LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    /**
     * W3C Trace Context (예: "00-{traceId}-{spanId}-01"). 도메인 트랜잭션 시점의 trace 를
     * 영속화해 폴러 발행 시 Kafka 헤더로 복원 → 비동기 경계에서도 단일 trace 보장.
     * 트레이싱이 비활성이거나 미캡처면 null.
     */
    private final String traceParent;
    /**
     * 사건이 <b>실제로 일어난</b> 시각 — UTC (DATA-STANDARD N4 봉투 필수).
     *
     * <p>{@code createdAt}(행 생성 시각, 레거시 LocalDateTime)과 구분한다. 재처리·백필로 나중에
     * 다시 기록돼도 occurredAt 은 원래 사건 시각을 유지해야 하므로, 순서 판정·지연(lag) 측정·
     * 늦게 도착한 이벤트 판별은 createdAt 이 아니라 이 필드를 봐야 한다.
     */
    private final OffsetDateTime occurredAt;
    /** 봉투/페이로드 스키마 버전 (N4). 소비측 버전 분기의 근거 — 신규는 1. */
    private final int eventVersion;
    /**
     * 발행 서비스 이름 (N4). 도메인은 자기 이름을 모르므로 null 로 두고, 영속 어댑터가
     * {@code spring.application.name} 으로 채운다.
     */
    private final String producer;

    private OutboxEvent(Long id,
                        String aggregateType,
                        String aggregateId,
                        String eventType,
                        UUID eventId,
                        String payload,
                        OutboxEventStatus status,
                        int retryCount,
                        String lastError,
                        LocalDateTime createdAt,
                        LocalDateTime publishedAt,
                        String traceParent,
                        OffsetDateTime occurredAt,
                        int eventVersion,
                        String producer) {
        this.id = id;
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.status = Objects.requireNonNull(status, "status");
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.publishedAt = publishedAt;
        this.traceParent = traceParent;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.eventVersion = eventVersion;
        this.producer = producer;
    }

    /**
     * 신규 PENDING 이벤트 생성. traceParent 미지정 (트레이싱 비활성 환경 호환).
     */
    public static OutboxEvent pending(String aggregateType, String aggregateId,
                                      String eventType, String payload) {
        return pending(aggregateType, aggregateId, eventType, payload, null);
    }

    /**
     * 신규 PENDING 이벤트 생성 + 현재 trace context 캡처. 도메인 트랜잭션과 같은 호출 스택에서
     * 호출되어야 의미가 있다 — 폴러 시점이 아닌 비즈니스 변경 시점의 trace 를 보존.
     */
    public static OutboxEvent pending(String aggregateType, String aggregateId,
                                      String eventType, String payload, String traceParent) {
        return pending(aggregateType, aggregateId, eventType, payload, traceParent, DEFAULT_EVENT_VERSION);
    }

    /**
     * 페이로드 스키마를 바꿔 버전을 올릴 때 쓰는 오버로드 (N4 {@code event_version}).
     *
     * <p>occurredAt 은 여기서 UTC 로 찍힌다 — 도메인 트랜잭션 시점이 곧 사건 시각이고,
     * 폴러가 나중에 집어가는 시각과 구분되어야 한다.
     */
    public static OutboxEvent pending(String aggregateType, String aggregateId, String eventType,
                                      String payload, String traceParent, int eventVersion) {
        // 저장소(timestamptz)의 해상도가 마이크로초라, 나노초 꼬리를 남기면 메모리 객체와
        // 영속 후 값이 달라진다(Windows 시계는 100ns 단위 → 대부분의 실행에서 꼬리가 남는다).
        // 사건 시각의 의미는 마이크로초로 충분하므로 생성 시점에 맞춰 잘라 왕복을 무손실로 만든다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new OutboxEvent(
                null,
                aggregateType,
                aggregateId,
                eventType,
                UUID.randomUUID(),
                payload,
                OutboxEventStatus.PENDING,
                0,
                null,
                // createdAt 은 레거시 의미(로컬 wall-clock)를 그대로 둔다 — 여기서 UTC 로 바꾸면
                // 기존 행과 9시간 어긋나 폴링 정렬(created_at ASC)이 뒤섞인다. UTC 시각은 occurredAt.
                LocalDateTime.now(),
                null,
                traceParent,
                now.atOffset(ZoneOffset.UTC),
                eventVersion,
                null   // producer — 영속 어댑터가 spring.application.name 으로 주입
        );
    }

    /**
     * 영속 상태에서 재구성 (어댑터에서만 사용). traceParent 누락 호환 호출.
     */
    public static OutboxEvent rehydrate(Long id, String aggregateType, String aggregateId,
                                        String eventType, UUID eventId, String payload,
                                        OutboxEventStatus status, int retryCount, String lastError,
                                        LocalDateTime createdAt, LocalDateTime publishedAt) {
        return rehydrate(id, aggregateType, aggregateId, eventType, eventId, payload,
                status, retryCount, lastError, createdAt, publishedAt, null);
    }

    /** 봉투 필드 누락 호환 호출 — occurredAt 은 createdAt 을 UTC 로 간주해 대체한다(레거시 행). */
    public static OutboxEvent rehydrate(Long id, String aggregateType, String aggregateId,
                                        String eventType, UUID eventId, String payload,
                                        OutboxEventStatus status, int retryCount, String lastError,
                                        LocalDateTime createdAt, LocalDateTime publishedAt,
                                        String traceParent) {
        return rehydrate(id, aggregateType, aggregateId, eventType, eventId, payload,
                status, retryCount, lastError, createdAt, publishedAt, traceParent,
                createdAt.atOffset(ZoneOffset.UTC), DEFAULT_EVENT_VERSION, null);
    }

    public static OutboxEvent rehydrate(Long id, String aggregateType, String aggregateId,
                                        String eventType, UUID eventId, String payload,
                                        OutboxEventStatus status, int retryCount, String lastError,
                                        LocalDateTime createdAt, LocalDateTime publishedAt,
                                        String traceParent, OffsetDateTime occurredAt,
                                        int eventVersion, String producer) {
        return new OutboxEvent(id, aggregateType, aggregateId, eventType, eventId, payload,
                status, retryCount, lastError, createdAt, publishedAt, traceParent,
                occurredAt, eventVersion, producer);
    }

    /** 영속 어댑터가 발행 서비스 이름을 채운다 — 도메인은 자기 배포 이름을 알지 못한다. */
    public OutboxEvent withProducer(String producer) {
        return new OutboxEvent(id, aggregateType, aggregateId, eventType, eventId, payload,
                status, retryCount, lastError, createdAt, publishedAt, traceParent,
                occurredAt, eventVersion, producer);
    }

    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
        // 재시도 한계는 스케줄러에서 판단 — 여기선 상태만 갱신
        if (this.retryCount >= 10) {
            this.status = OutboxEventStatus.FAILED;
        }
    }

    /**
     * 운영자가 FAILED 이벤트를 다시 발행 큐에 올린다.
     *
     * <p>retryCount 를 0 으로 초기화하고 PENDING 으로 되돌려, 다음 폴링 주기에 발행을 다시 시도한다.
     * lastError 는 보존하여 재시도 결정의 근거 추적이 가능하게 한다.
     *
     * <p>FAILED 상태가 아닐 때 호출되면 IllegalStateException — 정상 흐름에서는 발생하지 않음.
     */
    public void requeue() {
        if (this.status != OutboxEventStatus.FAILED) {
            throw new IllegalStateException(
                    "FAILED 상태의 이벤트만 재처리 가능합니다. 현재 상태: " + this.status);
        }
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
    }

    /**
     * 운영자가 FAILED 이벤트를 의도적으로 스킵한다 (수동 보정 완료 등).
     *
     * <p>PUBLISHED 로 마킹하되, lastError 필드에 스킵 사유를 기록해 사후 감사가 가능하게 한다.
     * 같은 컨슈머가 이미 PROCESSED 상태로 처리한 케이스에서 outbox 만 정리할 때 사용.
     */
    public void skip(String reason) {
        if (this.status != OutboxEventStatus.FAILED) {
            throw new IllegalStateException(
                    "FAILED 상태의 이벤트만 스킵 가능합니다. 현재 상태: " + this.status);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("스킵 사유는 필수입니다 (감사 추적용)");
        }
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.lastError = "[SKIPPED] " + reason;
    }

    public boolean isPending() {
        return status == OutboxEventStatus.PENDING;
    }

    public boolean isFailed() {
        return status == OutboxEventStatus.FAILED;
    }

    // Getters
    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public UUID getEventId() { return eventId; }
    public String getPayload() { return payload; }
    public OutboxEventStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public String getTraceParent() { return traceParent; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public int getEventVersion() { return eventVersion; }
    public String getProducer() { return producer; }
}
