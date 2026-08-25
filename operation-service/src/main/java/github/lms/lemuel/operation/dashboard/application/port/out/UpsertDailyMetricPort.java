package github.lms.lemuel.operation.dashboard.application.port.out;

import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 일별 집계 누적(원자적 upsert). */
public interface UpsertDailyMetricPort {

    /**
     * 이벤트 한 건을 누적한다.
     *
     * @param amount 금액. {@code null} 이면 금액을 읽지 못한 것으로 보고
     *               {@code amount_unknown_count} 를 올린다(합계는 건드리지 않는다).
     */
    void accumulate(LocalDate date, DashboardMetric metric, BigDecimal amount);
}
