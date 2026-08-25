package github.lms.lemuel.operation.dashboard.application.port.out;

import github.lms.lemuel.operation.dashboard.domain.DailyMetric;

import java.time.LocalDate;
import java.util.List;

/** 일별 집계 조회. */
public interface LoadDailyMetricPort {

    /** 그 날짜에 실제로 적재된 줄만 돌려준다 — 없는 지표를 0 으로 채우는 것은 서비스의 몫이다. */
    List<DailyMetric> findByDate(LocalDate date);

    /**
     * 기간(양끝 포함) 안에 <b>실제로 적재된</b> 줄만 날짜 오름차순으로 돌려준다.
     *
     * <p>{@link #findByDate} 와 같은 규칙으로 빈 날짜를 채우지 않는다. 어댑터가 채우면 날짜
     * 계산이 SQL 과 서비스 두 곳에 생기고, 그 둘이 타임존을 다르게 보는 날 조용히 어긋난다.
     * 채우는 일은 타임존을 아는 서비스 한 곳에서만 한다.
     *
     * <p>호출자는 기간 길이를 먼저 제한해야 한다 — 이 포트는 받은 만큼 전부 읽는다.
     */
    List<DailyMetric> findBetween(LocalDate from, LocalDate to);
}
