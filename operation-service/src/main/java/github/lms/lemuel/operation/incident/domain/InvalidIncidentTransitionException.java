package github.lms.lemuel.operation.incident.domain;

/**
 * 상태머신이 허용하지 않는 인시던트 전이 시도 — 웹 계층에서 409 Conflict 로 매핑된다.
 */
public class InvalidIncidentTransitionException extends IllegalStateException {

    private final IncidentStatus from;
    private final IncidentStatus to;

    public InvalidIncidentTransitionException(IncidentStatus from, IncidentStatus to) {
        super("인시던트 상태 전이 불가: %s → %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public IncidentStatus from() {
        return from;
    }

    public IncidentStatus to() {
        return to;
    }
}
