package github.lms.lemuel.operation.incident.adapter.in.web.dto;

import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentDetail;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 인시던트 단건 응답 — 목록 필드 + description/labels/annotations(runbook_url 포함) + 타임라인. */
public record IncidentDetailResponse(
        IncidentResponse incident,
        String description,
        Map<String, String> labels,
        Map<String, String> annotations,
        List<TimelineEntryResponse> timeline
) {
    public static IncidentDetailResponse from(IncidentDetail detail) {
        Incident i = detail.incident();
        return new IncidentDetailResponse(
                IncidentResponse.from(i), i.getDescription(), i.getLabels(), i.getAnnotations(),
                detail.timeline().stream().map(TimelineEntryResponse::from).toList());
    }

    public record TimelineEntryResponse(String eventType, String actor, String note, Instant createdAt) {
        public static TimelineEntryResponse from(IncidentTimelineEntry e) {
            return new TimelineEntryResponse(e.eventType().name(), e.actor(), e.note(), e.createdAt());
        }
    }
}
