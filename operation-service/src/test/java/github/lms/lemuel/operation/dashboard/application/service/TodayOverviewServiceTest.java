package github.lms.lemuel.operation.dashboard.application.service;

import github.lms.lemuel.operation.dashboard.application.port.out.LoadDailyMetricPort;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadOperationHealthPort;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import github.lms.lemuel.operation.dashboard.domain.TodayOverview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodayOverviewServiceTest {

    @Mock
    LoadDailyMetricPort metricPort;
    @Mock
    LoadOperationHealthPort healthPort;

    TodayOverviewService service;

    /** KST 로 2026-08-25 08:30 (= UTC 로는 아직 08-24 23:30). */
    private static final Instant NOW = Instant.parse("2026-08-24T23:30:00Z");
    private static final LocalDate KST_TODAY = LocalDate.of(2026, 8, 25);

    @BeforeEach
    void setUp() {
        service = new TodayOverviewService(metricPort, healthPort,
                Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Seoul");
    }

    private static DailyMetric stored(DashboardMetric metric, long count, String amount, Instant at) {
        return new DailyMetric(KST_TODAY, metric, count, new BigDecimal(amount), 0L, at);
    }

    /**
     * 시계가 UTC 여도 '오늘'은 KST 로 자른다. 이걸 놓치면 KST 오전 9시 이전에 대시보드를 연
     * 운영자에게 밤새 매출이 사라진 것처럼 보인다 — 실제로는 아직 UTC 로 어제이기 때문이다.
     */
    @Test
    @DisplayName("오늘의 기준은 UTC 가 아니라 설정된 타임존이다")
    void todayUsesConfiguredZone() {
        when(metricPort.findByDate(any())).thenReturn(List.of());

        TodayOverview overview = service.today();

        assertThat(overview.date()).isEqualTo(KST_TODAY);
        assertThat(overview.zone()).isEqualTo("Asia/Seoul");
        verify(metricPort).findByDate(KST_TODAY);
    }

    /**
     * 없는 지표를 빼 버리면 "오늘 환불 0건"과 "환불 카드가 사라짐"이 화면에서 구분되지 않는다.
     * 앞은 좋은 소식이고 뒤는 사고인데도.
     */
    @Test
    @DisplayName("집계 행이 없는 지표도 0 으로 채워 카드가 사라지지 않게 한다")
    void missingMetricsAreZeroFilled() {
        when(metricPort.findByDate(KST_TODAY)).thenReturn(
                List.of(stored(DashboardMetric.ORDER_CREATED, 3, "45000", NOW)));

        TodayOverview overview = service.today();

        assertThat(overview.metrics()).hasSize(DashboardMetric.values().length);
        assertThat(overview.metrics())
                .filteredOn(m -> m.metric() == DashboardMetric.PAYMENT_REFUNDED)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.eventCount()).isZero();
                    assertThat(m.amountSum()).isEqualByComparingTo("0");
                });
    }

    @Test
    @DisplayName("기준 시각은 반영된 이벤트 중 가장 최근 시각이다")
    void asOfIsTheLatestUpdate() {
        Instant older = NOW.minusSeconds(600);
        when(metricPort.findByDate(KST_TODAY)).thenReturn(List.of(
                stored(DashboardMetric.ORDER_CREATED, 3, "45000", older),
                stored(DashboardMetric.PAYMENT_CAPTURED, 2, "30000", NOW)));

        assertThat(service.today().asOf()).isEqualTo(NOW);
    }

    /**
     * 오늘 이벤트가 하나도 없을 때 어제 시각을 대신 보여 주면, 화면이 "방금까지 집계했는데
     * 아무 일도 없었다"고 <b>거짓말</b>을 하게 된다. 비워 두면 화면이 "아직 없음"이라고 말한다.
     */
    @Test
    @DisplayName("오늘 반영된 이벤트가 없으면 기준 시각은 비어 있다")
    void asOfIsNullWhenNothingHappenedYet() {
        when(metricPort.findByDate(KST_TODAY)).thenReturn(List.of());

        assertThat(service.today().asOf()).isNull();
    }

    /**
     * 실패 알림의 '오늘'도 지표와 같은 타임존으로 잘라야 한다. 여기만 UTC 로 자르면 같은 화면
     * 안에 두 개의 '오늘'이 생긴다.
     */
    @Test
    @DisplayName("실패 알림 집계 시작점도 같은 타임존의 자정이다")
    void failedDispatchWindowStartsAtLocalMidnight() {
        when(metricPort.findByDate(KST_TODAY)).thenReturn(List.of());
        when(healthPort.countFailedDispatchesSince(any())).thenReturn(2L);
        when(healthPort.countOpenIncidents()).thenReturn(1L);

        TodayOverview overview = service.today();

        verify(healthPort).countFailedDispatchesSince(
                KST_TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        assertThat(overview.failedDispatches()).isEqualTo(2L);
        assertThat(overview.openIncidents()).isEqualTo(1L);
    }

    @Test
    @DisplayName("날짜를 지정하면 그 날짜로 조회한다")
    void onDateQueriesTheGivenDay() {
        LocalDate yesterday = KST_TODAY.minusDays(1);
        when(metricPort.findByDate(yesterday)).thenReturn(List.of());

        assertThat(service.onDate(yesterday).date()).isEqualTo(yesterday);
    }
}
