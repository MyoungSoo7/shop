package github.lms.lemuel.operation.dashboard.application.port.out;

import github.lms.lemuel.operation.dashboard.domain.DailyMetric;

import java.time.LocalDate;
import java.util.List;

/** 일별 집계 조회. */
public interface LoadDailyMetricPort {

    /** 그 날짜에 실제로 적재된 줄만 돌려준다 — 없는 지표를 0 으로 채우는 것은 서비스의 몫이다. */
    List<DailyMetric> findByDate(LocalDate date);
}
