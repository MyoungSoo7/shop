package github.lms.lemuel.operation.dashboard.application.port.in;

import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;

import java.math.BigDecimal;
import java.time.Instant;

/** 도메인 이벤트 한 건을 일별 집계에 반영한다. */
public interface RecordDailyMetricUseCase {

    /**
     * @param occurredAt 사건이 일어난 시각. 어느 날짜 칸에 들어갈지를 이 값이 정한다.
     * @param amount     금액. {@code null} 이면 "금액 미상"으로 기록한다(합계에 추측을 넣지 않는다).
     */
    void record(DashboardMetric metric, Instant occurredAt, BigDecimal amount);
}
