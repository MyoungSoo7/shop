package github.lms.lemuel.operation.incident.adapter.in.web.dto;

import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery.IncidentSummary;

import java.util.LinkedHashMap;
import java.util.Map;

/** 대시보드 요약 응답 — Phase 4 일일 브리핑이 동일 재료를 재사용한다. */
public record IncidentSummaryResponse(
        String window,
        long openTotal,
        Map<String, Long> byStatus,
        Map<String, Long> byCategory,
        Map<String, Long> bySeverity,
        Long mttrSeconds
) {
    public static IncidentSummaryResponse from(String window, IncidentSummary s) {
        return new IncidentSummaryResponse(window, s.openTotal(),
                names(s.byStatus()), names(s.byCategory()), names(s.bySeverity()), s.mttrSeconds());
    }

    private static <E extends Enum<E>> Map<String, Long> names(Map<E, Long> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k.name(), v));
        return result;
    }
}
