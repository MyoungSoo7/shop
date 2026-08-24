package github.lms.lemuel.operation.incident.application.port.in;

/** 존재하지 않는 인시던트 접근 — 웹 계층에서 404 로 매핑된다. */
public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(Long incidentId) {
        super("인시던트 없음: id=" + incidentId);
    }
}
