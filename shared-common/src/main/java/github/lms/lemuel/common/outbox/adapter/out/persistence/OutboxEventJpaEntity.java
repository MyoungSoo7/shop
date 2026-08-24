package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * W3C Trace Context. 도메인 트랜잭션 시점의 trace 를 영속화해 비동기 발행 시
     * Kafka 헤더로 복원 → 단일 trace 추적 보장.
     */
    @Column(name = "trace_parent", length = 64)
    private String traceParent;

    /** 사건 발생 시각 — UTC(timestamptz). 행 생성 시각인 created_at 과 구분 (N4). */
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    /** 봉투/페이로드 스키마 버전 (N4). */
    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    /** 발행 서비스 이름 (N4) — spring.application.name. */
    @Column(name = "producer", length = 64)
    private String producer;

    protected OutboxEventJpaEntity() { }

    public OutboxEventJpaEntity(Long id, String aggregateType, String aggregateId, String eventType,
                                UUID eventId, String payload, OutboxEventStatus status, int retryCount,
                                String lastError, LocalDateTime createdAt, LocalDateTime publishedAt) {
        this(id, aggregateType, aggregateId, eventType, eventId, payload,
                status, retryCount, lastError, createdAt, publishedAt, null);
    }

    public OutboxEventJpaEntity(Long id, String aggregateType, String aggregateId, String eventType,
                                UUID eventId, String payload, OutboxEventStatus status, int retryCount,
                                String lastError, LocalDateTime createdAt, LocalDateTime publishedAt,
                                String traceParent) {
        this(id, aggregateType, aggregateId, eventType, eventId, payload, status, retryCount,
                lastError, createdAt, publishedAt, traceParent,
                createdAt == null ? null : createdAt.atOffset(java.time.ZoneOffset.UTC),
                OutboxEvent.DEFAULT_EVENT_VERSION, null);
    }

    public OutboxEventJpaEntity(Long id, String aggregateType, String aggregateId, String eventType,
                                UUID eventId, String payload, OutboxEventStatus status, int retryCount,
                                String lastError, LocalDateTime createdAt, LocalDateTime publishedAt,
                                String traceParent, OffsetDateTime occurredAt, int eventVersion,
                                String producer) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventId = eventId;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.traceParent = traceParent;
        this.occurredAt = occurredAt;
        this.eventVersion = eventVersion;
        this.producer = producer;
    }

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
