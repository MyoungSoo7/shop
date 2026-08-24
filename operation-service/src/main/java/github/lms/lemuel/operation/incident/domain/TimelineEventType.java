package github.lms.lemuel.operation.incident.domain;

/** 인시던트 타임라인 이벤트 종류 — incident_timeline.event_type CHECK 제약과 일치. */
public enum TimelineEventType {
    OPENED,
    REFIRED,
    ACKNOWLEDGED,
    RESOLVED,
    /** Alertmanager resolved 수신에 의한 자동 해제 (actor='alertmanager') */
    AUTO_RESOLVED,
    FALSE_POSITIVE,
    COMMENT
}
