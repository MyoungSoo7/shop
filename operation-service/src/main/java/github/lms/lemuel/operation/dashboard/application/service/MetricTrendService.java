package github.lms.lemuel.operation.dashboard.application.service;

import github.lms.lemuel.operation.dashboard.application.port.in.ViewMetricTrendUseCase;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadDailyMetricPort;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import github.lms.lemuel.operation.dashboard.domain.MetricTrend;
import github.lms.lemuel.operation.dashboard.domain.MetricTrend.MetricTotal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 기간 추이 조립.
 *
 * <p>{@code ops_daily_metric} 만 읽는다. {@code TodayOverviewService} 와 같은 이유로 다른 서비스의
 * DB 도 API 도 부르지 않는다 — 장애를 보라고 만든 화면이 장애 때 제일 먼저 죽으면 안 된다.
 *
 * <p>이 서비스가 실제로 하는 일은 세 가지다: <b>기간을 정하고</b>, <b>구멍을 0 으로 메우고</b>,
 * <b>합계와 그 합계를 믿어도 되는지를 함께 계산한다</b>. 셋 다 화면에 맡기면 조용히 틀리는
 * 종류의 계산이다.
 */
@Service
public class MetricTrendService implements ViewMetricTrendUseCase {

    /**
     * 기본 조회 기간(일). 오늘을 포함해 30일이다.
     *
     * <p>"최근"의 뜻을 서버가 한 곳에서 정한다. 화면마다 정하면 대시보드의 30일과 리포트의
     * 30일이 다른 날 시작하는데, 두 화면을 나란히 놓기 전까지는 아무도 모른다.
     */
    public static final int DEFAULT_RANGE_DAYS = 30;

    /**
     * 최대 조회 기간(일).
     *
     * <p>상한이 없으면 {@code from=1970-01-01} 한 번으로 테이블 전체를 읽어 한 응답에 싣게 된다.
     * 윤년을 포함한 1년이 한 번에 볼 수 있는 가장 긴 구간이라고 보고 366 으로 둔다 —
     * 그보다 긴 비교는 추이가 아니라 리포트의 일이다.
     */
    public static final int MAX_RANGE_DAYS = 366;

    private final LoadDailyMetricPort metricPort;
    private final Clock clock;
    private final ZoneId zone;

    public MetricTrendService(LoadDailyMetricPort metricPort,
                              Clock clock,
                              @Value("${app.ops.dashboard.zone:Asia/Seoul}") String zone) {
        this.metricPort = metricPort;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Override
    @Transactional(readOnly = true)
    public MetricTrend view(TrendQuery query) {
        List<DashboardMetric> metrics = resolveMetrics(query.metrics());

        // 종료일 기본값은 "오늘"이고, 그 오늘은 집계를 적재할 때 쓴 타임존과 같아야 한다.
        // 여기만 시스템 기본 타임존으로 두면 KST 오전 9시 이전에 여는 사람에게 마지막 하루가
        // 통째로 비어 보인다 — 데이터가 없는 게 아니라 다른 날짜를 물어본 것이다.
        LocalDate to = query.to() != null ? query.to() : LocalDate.now(clock.withZone(zone));
        LocalDate from = query.from() != null
                ? query.from()
                : to.minusDays(DEFAULT_RANGE_DAYS - 1L);

        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "조회 시작일이 종료일보다 늦습니다: from=" + from + ", to=" + to);
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "조회 기간이 너무 깁니다: " + days + "일 (최대 " + MAX_RANGE_DAYS + "일)");
        }

        Map<Key, DailyMetric> stored = new HashMap<>();
        for (DailyMetric row : metricPort.findBetween(from, to)) {
            stored.put(new Key(row.date(), row.metric()), row);
        }

        // 구멍을 0 으로 메운다. 빈 날짜를 빼고 보내면 꺾은선의 x축이 좁혀지면서 "주문이 없던
        // 날"이 그래프에서 아예 사라진다 — 장사가 안 된 날을 못 본 것이 아니라 없던 일이 된다.
        List<DailyMetric> series = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (DashboardMetric metric : metrics) {
                DailyMetric row = stored.get(new Key(date, metric));
                series.add(row != null ? row : DailyMetric.empty(date, metric));
            }
        }

        return new MetricTrend(from, to, zone.getId(), metrics, series, totals(metrics, series),
                series.stream()
                        .map(DailyMetric::updatedAt)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null));
    }

    /**
     * 요청이 준 지표 이름을 정규화한다.
     *
     * <p>모르는 이름은 <b>무시하지 않고 거부한다</b>. 오타를 조용히 버리면 화면은 자기가 요청한
     * 지표가 빠진 줄 모르고 남은 지표만 그리는데, 그 그래프는 비어 있지 않아서 틀렸다는
     * 신호가 없다. 없는 것을 물었으면 없다고 답해야 한다.
     *
     * <p>중복은 제거하되 선언 순서를 유지한다({@code LinkedHashSet}). 요청 순서를 따르면 같은
     * 지표 집합이 요청마다 다른 순서로 그려져 색이 바뀐다.
     */
    private static List<DashboardMetric> resolveMetrics(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of(DashboardMetric.values());
        }
        LinkedHashSet<DashboardMetric> resolved = new LinkedHashSet<>();
        for (String name : requested) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalized = name.trim().toUpperCase();
            resolved.add(Arrays.stream(DashboardMetric.values())
                    .filter(m -> m.name().equals(normalized))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "알 수 없는 지표입니다: " + name + " (가능한 값: "
                                    + Arrays.toString(DashboardMetric.values()) + ")")));
        }
        if (resolved.isEmpty()) {
            return List.of(DashboardMetric.values());
        }
        // 선언 순서로 되돌린다 — 요청이 순서를 정하면 같은 집합이 매번 다르게 그려진다.
        return Arrays.stream(DashboardMetric.values()).filter(resolved::contains).toList();
    }

    /**
     * 기간 합계.
     *
     * <p>{@code amountUnknownCount} 도 함께 더한다. 이 값을 버리고 금액만 더하면 "일부 미상"이던
     * 하루가 기간 합계에서는 정확한 값으로 승격된다 — 모르는 것이 합치는 과정에서 사라지면 안 된다.
     */
    private static List<MetricTotal> totals(List<DashboardMetric> metrics, List<DailyMetric> series) {
        return metrics.stream()
                .map(metric -> {
                    long count = 0;
                    BigDecimal amount = BigDecimal.ZERO;
                    long unknown = 0;
                    for (DailyMetric row : series) {
                        if (row.metric() != metric) {
                            continue;
                        }
                        count += row.eventCount();
                        amount = amount.add(row.amountSum() == null ? BigDecimal.ZERO : row.amountSum());
                        unknown += row.amountUnknownCount();
                    }
                    return new MetricTotal(metric, count, amount, unknown);
                })
                .toList();
    }

    /** 날짜×지표 조회 키. */
    private record Key(LocalDate date, DashboardMetric metric) {
    }
}
