package github.lms.lemuel.operation.incident.adapter.out.persistence;

import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import github.lms.lemuel.operation.incident.domain.TimelineEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** incident_timeline 테이블 매핑 — 불변 이력(INSERT only). */
@Entity
@Table(name = "incident_timeline")
public class IncidentTimelineJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private TimelineEventType eventType;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IncidentTimelineJpaEntity() {
    }

    public static IncidentTimelineJpaEntity fromDomain(IncidentTimelineEntry entry) {
        IncidentTimelineJpaEntity e = new IncidentTimelineJpaEntity();
        e.incidentId = entry.incidentId();
        e.eventType = entry.eventType();
        e.actor = entry.actor();
        e.note = entry.note();
        e.createdAt = entry.createdAt();
        return e;
    }

    public IncidentTimelineEntry toDomain() {
        return new IncidentTimelineEntry(id, incidentId, eventType, actor, note, createdAt);
    }
}
