package github.lms.lemuel.operation.incident.application.port.out;

import github.lms.lemuel.operation.incident.domain.Incident;

public interface SaveIncidentPort {

    /**
     * 신규(id null)면 INSERT, 기존이면 낙관적 락(@Version) 갱신.
     *
     * <p>동시 webhook 경쟁으로 uq_incident_active 위반 시
     * {@code DataIntegrityViolationException} — 호출자(IngestAlertService)가 catch 후
     * 갱신 경로로 폴백한다.
     *
     * @return 영속 id 가 채워진 인시던트
     */
    Incident save(Incident incident);
}
