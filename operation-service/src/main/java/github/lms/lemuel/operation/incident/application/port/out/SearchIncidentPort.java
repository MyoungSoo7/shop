package github.lms.lemuel.operation.incident.application.port.out;

import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentSearchCondition;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentSummary;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.PageResult;
import github.lms.lemuel.operation.incident.domain.Incident;

import java.time.Instant;

public interface SearchIncidentPort {

    PageResult<Incident> search(IncidentSearchCondition condition);

    /** @param from 요약 window 시작 시각 (byStatus/byCategory/bySeverity/mttr 의 기준) */
    IncidentSummary summarize(Instant from);
}
