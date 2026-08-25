package github.lms.lemuel.operation.dashboard.application.service;

import github.lms.lemuel.operation.dashboard.application.port.in.ViewTodayOverviewUseCase;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadDailyMetricPort;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadOperationHealthPort;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import github.lms.lemuel.operation.dashboard.domain.TodayOverview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 대시보드 한 화면치 조립.
 *
 * <p><b>이 서비스가 하지 않는 일</b>이 설계의 핵심이다 — 다른 서비스의 DB 를 읽지 않고, 다른
 * 서비스의 API 를 부르지도 않는다. 필요한 숫자는 이미 이벤트로 흘러들어와 집계 테이블에 있다.
 * 대시보드가 원본을 되물으러 나가는 순간, 그 화면은 자기가 보여 주는 모든 서비스가 살아 있을
 * 때만 뜨는 화면이 된다 — 장애를 보라고 만든 화면이 장애 때 제일 먼저 죽는다.
 */
@Service
public class TodayOverviewService implements ViewTodayOverviewUseCase {

    private final LoadDailyMetricPort metricPort;
    private final LoadOperationHealthPort healthPort;
    private final Clock clock;
    private final ZoneId zone;

    public TodayOverviewService(LoadDailyMetricPort metricPort,
                                LoadOperationHealthPort healthPort,
                                Clock clock,
                                @Value("${app.ops.dashboard.zone:Asia/Seoul}") String zone) {
        this.metricPort = metricPort;
        this.healthPort = healthPort;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Override
    @Transactional(readOnly = true)
    public TodayOverview today() {
        return assemble(LocalDate.now(clock.withZone(zone)));
    }

    @Override
    @Transactional(readOnly = true)
    public TodayOverview onDate(LocalDate date) {
        return assemble(date);
    }

    /**
     * 두 진입점이 공유하는 조립부. {@code today()} 가 {@code onDate()} 를 직접 부르면 자기호출이라
     * 프록시를 타지 않아 <b>안쪽 {@code @Transactional} 이 조용히 무효</b>가 된다 — 여기서는 바깥
     * 애노테이션이 이미 걸려 있어 결과는 같지만, 그 사실을 코드만 보고는 알 수 없다. 조립부를
     * 애노테이션 없는 private 로 내려 애노테이션이 붙은 자리는 전부 프록시 경계에 두었다.
     */
    private TodayOverview assemble(LocalDate date) {
        Map<DashboardMetric, DailyMetric> stored = new EnumMap<>(DashboardMetric.class);
        metricPort.findByDate(date).forEach(row -> stored.put(row.metric(), row));

        // 빠진 지표를 0 으로 채운다. 없는 줄을 그냥 빼면 "오늘 환불 0건"과 "환불 카드가 사라짐"이
        // 화면에서 구분되지 않는다 — 후자는 사고이고 전자는 좋은 소식인데도.
        List<DailyMetric> metrics = Arrays.stream(DashboardMetric.values())
                .map(metric -> stored.getOrDefault(metric, DailyMetric.empty(date, metric)))
                .toList();

        Instant asOf = metrics.stream()
                .map(DailyMetric::updatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // 실패 알림은 "오늘" 기준이라 날짜 경계를 지표와 같은 타임존으로 맞춘다. 여기만 UTC 로
        // 자르면 같은 화면 안에서 두 개의 '오늘'이 공존한다.
        Instant dayStart = date.atStartOfDay(zone).toInstant();

        return new TodayOverview(
                date,
                zone.getId(),
                asOf,
                metrics,
                healthPort.countOpenIncidents(),
                healthPort.countFailedDispatchesSince(dayStart));
    }
}
