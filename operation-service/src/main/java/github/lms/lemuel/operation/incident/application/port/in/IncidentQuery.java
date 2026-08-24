package github.lms.lemuel.operation.incident.application.port.in;

import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import github.lms.lemuel.operation.incident.domain.SignalCategory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 인시던트 조회 유스케이스 — 목록(필터+페이징), 단건(+타임라인), 대시보드 요약. */
public interface IncidentQuery {

    PageResult<Incident> search(IncidentSearchCondition condition);

    /** @throws IncidentNotFoundException 대상 없음 (웹 계층 404) */
    IncidentDetail get(Long incidentId);

    IncidentSummary summary(Duration window);

    /** 목록 필터 — null 필드는 조건 미적용. 정렬은 last_seen_at DESC 고정. */
    record IncidentSearchCondition(
            IncidentStatus status,
            SignalCategory category,
            IncidentSeverity severity,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
    }

    record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    record IncidentDetail(Incident incident, List<IncidentTimelineEntry> timeline) {
    }

    /**
     * 대시보드 요약 — Phase 4 일일 브리핑이 동일 재료를 재사용한다.
     *
     * @param openTotal    현재 활성(OPEN+ACKNOWLEDGED) 전체 건수 (window 무관)
     * @param byStatus     window 내 최초 발생(first_seen_at) 기준 상태별 건수
     * @param mttrSeconds  window 내 RESOLVED 건의 평균 해소 시간(초) — 해당 건 없으면 null
     */
    record IncidentSummary(
            long openTotal,
            Map<IncidentStatus, Long> byStatus,
            Map<SignalCategory, Long> byCategory,
            Map<IncidentSeverity, Long> bySeverity,
            Long mttrSeconds
    ) {
    }
}
