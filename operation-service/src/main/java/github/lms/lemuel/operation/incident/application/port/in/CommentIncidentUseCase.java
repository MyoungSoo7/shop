package github.lms.lemuel.operation.incident.application.port.in;

import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;

/** 인시던트 코멘트 추가 — 상태 전이 없이 타임라인에만 기록한다. */
public interface CommentIncidentUseCase {

    IncidentTimelineEntry comment(Long incidentId, String actor, String note);
}
