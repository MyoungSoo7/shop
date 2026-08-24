package github.lms.lemuel.operation.incident.adapter.out.persistence;

import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * incidents 테이블 매핑 (V1__incident_core.sql).
 *
 * <p>created_at/updated_at 은 DB DEFAULT NOW() 에 위임(insertable=false) — 어댑터가
 * 도메인 스냅샷으로 detached 엔티티를 재구성해 merge 하는 방식이라, 엔티티에 없는
 * 감사 컬럼을 merge 가 덮어쓰지 않게 한다. {@code @Version} 은 도메인이 들고 온 값
 * 그대로 실려 detached merge 에서도 낙관적 락이 동작한다.
 */
@Entity
@Table(name = "incidents")
public class IncidentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "correlation_key", nullable = false, length = 128)
    private String correlationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SignalCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 50)
    private String service;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> labels;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> annotations;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "last_refire_logged_at")
    private Instant lastRefireLoggedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected IncidentJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static IncidentJpaEntity fromDomain(github.lms.lemuel.operation.incident.domain.Incident incident) {
        IncidentJpaEntity e = new IncidentJpaEntity();
        e.id = incident.getId();
        e.correlationKey = incident.getCorrelationKey();
        e.source = incident.getSource();
        e.category = incident.getCategory();
        e.severity = incident.getSeverity();
        e.status = incident.getStatus();
        e.title = incident.getTitle();
        e.description = incident.getDescription();
        e.service = incident.getService();
        e.labels = incident.getLabels();
        e.annotations = incident.getAnnotations();
        e.firstSeenAt = incident.getFirstSeenAt();
        e.lastSeenAt = incident.getLastSeenAt();
        e.occurrenceCount = incident.getOccurrenceCount();
        e.lastRefireLoggedAt = incident.getLastRefireLoggedAt();
        e.acknowledgedAt = incident.getAcknowledgedAt();
        e.acknowledgedBy = incident.getAcknowledgedBy();
        e.resolvedAt = incident.getResolvedAt();
        e.resolvedBy = incident.getResolvedBy();
        e.version = incident.getVersion();
        return e;
    }

    public github.lms.lemuel.operation.incident.domain.Incident toDomain() {
        return github.lms.lemuel.operation.incident.domain.Incident.builder()
                .id(id)
                .correlationKey(correlationKey)
                .source(source)
                .category(category)
                .severity(severity)
                .status(status)
                .title(title)
                .description(description)
                .service(service)
                .labels(labels)
                .annotations(annotations)
                .firstSeenAt(firstSeenAt)
                .lastSeenAt(lastSeenAt)
                .occurrenceCount(occurrenceCount)
                .lastRefireLoggedAt(lastRefireLoggedAt)
                .acknowledgedAt(acknowledgedAt)
                .acknowledgedBy(acknowledgedBy)
                .resolvedAt(resolvedAt)
                .resolvedBy(resolvedBy)
                .version(version)
                .build();
    }

    public Long getId() {
        return id;
    }
}
