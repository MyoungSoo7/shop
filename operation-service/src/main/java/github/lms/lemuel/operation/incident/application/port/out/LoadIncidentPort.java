package github.lms.lemuel.operation.incident.application.port.out;

import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSource;

import java.util.Optional;

public interface LoadIncidentPort {

    /** 활성(OPEN/ACKNOWLEDGED) 인시던트 단건 — uq_incident_active 가 최대 1건을 보장한다. */
    Optional<Incident> findActive(IncidentSource source, String correlationKey);

    Optional<Incident> findById(Long id);
}
