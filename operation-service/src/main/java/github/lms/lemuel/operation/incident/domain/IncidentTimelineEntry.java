package github.lms.lemuel.operation.incident.domain;

import java.time.Instant;

/**
 * 인시던트 타임라인 항목 — 운영자 조작·시스템 이벤트의 불변 이력 (감사 이력 겸용).
 *
 * @param id         영속 식별자 (미저장 시 null)
 * @param incidentId 소속 인시던트
 * @param eventType  이벤트 종류
 * @param actor      운영자 username 또는 {@link Incident#AUTO_ACTOR}
 * @param note       선택 메모
 * @param createdAt  기록 시각
 */
public record IncidentTimelineEntry(
        Long id,
        Long incidentId,
        TimelineEventType eventType,
        String actor,
        String note,
        Instant createdAt
) {
    public static IncidentTimelineEntry of(Long incidentId, TimelineEventType eventType,
                                           String actor, String note, Instant createdAt) {
        return new IncidentTimelineEntry(null, incidentId, eventType, actor, note, createdAt);
    }
}
