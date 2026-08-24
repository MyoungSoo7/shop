package github.lms.lemuel.operation.incident.adapter.in.web.dto;

import github.lms.lemuel.operation.incident.domain.Incident;

import java.time.Instant;

/** 인시던트 목록 항목 응답. */
public record IncidentResponse(
        Long id,
        String correlationKey,
        String source,
        String category,
        String severity,
        String status,
        String title,
        String service,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int occurrenceCount,
        String acknowledgedBy,
        Instant acknowledgedAt,
        String resolvedBy,
        Instant resolvedAt
) {
    public static IncidentResponse from(Incident i) {
        return new IncidentResponse(
                i.getId(), i.getCorrelationKey(), i.getSource().name(), i.getCategory().name(),
                i.getSeverity().name(), i.getStatus().name(), i.getTitle(), i.getService(),
                i.getFirstSeenAt(), i.getLastSeenAt(), i.getOccurrenceCount(),
                i.getAcknowledgedBy(), i.getAcknowledgedAt(), i.getResolvedBy(), i.getResolvedAt());
    }
}
