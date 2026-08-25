package github.lms.lemuel.operation.dashboard.application.service;

import github.lms.lemuel.operation.dashboard.application.port.in.ViewMetricTrendUseCase.TrendQuery;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadDailyMetricPort;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import github.lms.lemuel.operation.dashboard.domain.MetricTrend;
import github.lms.lemuel.operation.dashboard.domain.MetricTrend.MetricTotal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 기간 추이 조립 단위 테스트.
 *
 * <p>여기서 지키는 것은 셋이다 — <b>구멍을 0 으로 메우는가</b>(빼면 장사가 안 된 날이 그래프에서
 * 사라진다), <b>'오늘'을 어느 타임존으로 자르는가</b>(어긋나면 마지막 하루가 통째로 빈다),
 * <b>모르는 금액이 합계에서 사라지지 않는가</b>(사라지면 하한이 정확한 값으로 승격된다).
 */
@ExtendWith(MockitoExtension.class)
class MetricTrendServiceTest {

    @Mock
    LoadDailyMetricPort metricPort;

    MetricTrendService service;

    /** KST 로 2026-08-25 08:30 (= UTC 로는 아직 08-24 23:30). */
    private static final Instant NOW = Instant.parse("2026-08-24T23:30:00Z");
    private static final LocalDate KST_TODAY = LocalDate.of(2026, 8, 25);

    @BeforeEach
    void setUp() {
        service = new MetricTrendService(metricPort, Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Seoul");
    }

    private static DailyMetric stored(LocalDate date, DashboardMetric metric,
                                      long count, String amount, long unknown) {
        return new DailyMetric(date, metric, count, new BigDecimal(amount), unknown,
                date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static TrendQuery range(LocalDate from, LocalDate to) {
        return new TrendQuery(from, to, null);
    }

    private static MetricTotal totalOf(MetricTrend trend, DashboardMetric metric) {
        return trend.totals().stream().filter(t -> t.metric() == metric).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("종료일 기본값은 UTC 가 아니라 설정된 타임존의 오늘이다")
    void defaultsToTodayInConfiguredZone() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        MetricTrend trend = service.view(new TrendQuery(null, null, null));

        assertThat(trend.to()).isEqualTo(KST_TODAY);
        assertThat(trend.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("기본 기간은 오늘을 포함한 30일 — 29일 전부터다")
    void defaultRangeIncludesToday() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        MetricTrend trend = service.view(new TrendQuery(null, null, null));

        assertThat(trend.from()).isEqualTo(KST_TODAY.minusDays(29));
        assertThat(trend.to()).isEqualTo(KST_TODAY);
        assertThat(trend.series())
                .hasSize(MetricTrendService.DEFAULT_RANGE_DAYS * DashboardMetric.values().length);
    }

    /**
     * 이 테스트가 이 기능의 존재 이유다. 값이 없는 날을 빼고 보내면 꺾은선의 x축이 좁혀져
     * "주문이 없던 날"이 그래프에서 아예 사라진다 — 못 본 것이 아니라 없던 일이 된다.
     */
    @Test
    @DisplayName("값이 없는 날은 0 으로 채운다 — 빠진 날과 0건은 다르다")
    void fillsGapsWithZero() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 22);
        when(metricPort.findBetween(from, to))
                .thenReturn(List.of(stored(LocalDate.of(2026, 8, 21), DashboardMetric.ORDER_CREATED,
                        3, "30000", 0)));

        MetricTrend trend = service.view(new TrendQuery(from, to,
                List.of(DashboardMetric.ORDER_CREATED.name())));

        assertThat(trend.series()).extracting(DailyMetric::date)
                .containsExactly(from, LocalDate.of(2026, 8, 21), to);
        assertThat(trend.series()).extracting(DailyMetric::eventCount)
                .containsExactly(0L, 3L, 0L);
    }

    @Test
    @DisplayName("날짜 오름차순으로 나온다 — 어댑터 정렬을 서비스가 흐트러뜨리지 않는다")
    void seriesIsChronological() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 24);
        when(metricPort.findBetween(from, to)).thenReturn(List.of());

        MetricTrend trend = service.view(new TrendQuery(from, to,
                List.of(DashboardMetric.ORDER_CREATED.name())));

        assertThat(trend.series()).extracting(DailyMetric::date).isSorted();
    }

    @Test
    @DisplayName("기간 합계는 금액 미상 건수도 같이 더한다 — 합치는 과정에서 '모름'이 사라지면 안 된다")
    void totalsCarryUnknownCount() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 21);
        when(metricPort.findBetween(from, to)).thenReturn(List.of(
                stored(from, DashboardMetric.PAYMENT_REFUNDED, 2, "1000", 1),
                stored(to, DashboardMetric.PAYMENT_REFUNDED, 3, "2000", 0)));

        MetricTrend trend = service.view(new TrendQuery(from, to,
                List.of(DashboardMetric.PAYMENT_REFUNDED.name())));

        MetricTotal total = totalOf(trend, DashboardMetric.PAYMENT_REFUNDED);
        assertThat(total.eventCount()).isEqualTo(5);
        assertThat(total.amountSum()).isEqualByComparingTo("3000");
        assertThat(total.amountUnknownCount()).isEqualTo(1);
        assertThat(total.amountComplete()).isFalse();
    }

    @Test
    @DisplayName("미상이 하나도 없으면 합계를 사실로 읽어도 된다")
    void totalsCompleteWhenNothingUnknown() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        when(metricPort.findBetween(from, from)).thenReturn(List.of(
                stored(from, DashboardMetric.ORDER_CREATED, 2, "5000", 0)));

        MetricTrend trend = service.view(new TrendQuery(from, from,
                List.of(DashboardMetric.ORDER_CREATED.name())));

        assertThat(totalOf(trend, DashboardMetric.ORDER_CREATED).amountComplete()).isTrue();
    }

    @Test
    @DisplayName("지표를 안 주면 전 항목 — 선언 순서 그대로")
    void allMetricsByDefault() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        MetricTrend trend = service.view(range(KST_TODAY, KST_TODAY));

        assertThat(trend.metrics()).containsExactly(DashboardMetric.values());
    }

    @Test
    @DisplayName("요청 순서가 아니라 선언 순서로 그린다 — 같은 집합이 요청마다 다른 색이 되면 안 된다")
    void metricOrderIsDeclarationOrder() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        MetricTrend trend = service.view(new TrendQuery(KST_TODAY, KST_TODAY,
                List.of(DashboardMetric.USER_REGISTERED.name(), DashboardMetric.ORDER_CREATED.name())));

        assertThat(trend.metrics())
                .containsExactly(DashboardMetric.ORDER_CREATED, DashboardMetric.USER_REGISTERED);
    }

    @Test
    @DisplayName("소문자·공백도 받는다")
    void metricNameIsNormalized() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        MetricTrend trend = service.view(new TrendQuery(KST_TODAY, KST_TODAY, List.of("  order_created ")));

        assertThat(trend.metrics()).containsExactly(DashboardMetric.ORDER_CREATED);
    }

    /**
     * 오타를 조용히 버리면 화면은 자기가 요청한 지표가 빠진 줄 모르고 남은 것만 그린다.
     * 그 그래프는 비어 있지 않아서 틀렸다는 신호가 없다.
     */
    @Test
    @DisplayName("모르는 지표는 무시가 아니라 거부다")
    void unknownMetricRejected() {
        assertThatThrownBy(() -> service.view(new TrendQuery(KST_TODAY, KST_TODAY, List.of("REVENUE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVENUE");

        verifyNoInteractions(metricPort);
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 거부한다")
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> service.view(range(KST_TODAY, KST_TODAY.minusDays(1))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(metricPort);
    }

    /**
     * 상한이 없으면 {@code from=1970-01-01} 한 번으로 테이블 전체가 한 응답에 실린다.
     */
    @Test
    @DisplayName("최대 기간을 넘으면 거부한다")
    void rejectsTooLongRange() {
        LocalDate to = KST_TODAY;
        LocalDate from = to.minusDays(MetricTrendService.MAX_RANGE_DAYS);

        assertThatThrownBy(() -> service.view(range(from, to)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(metricPort);
    }

    @Test
    @DisplayName("최대 기간 정확히는 통과한다 — 경계에서 하루 어긋나지 않는다")
    void allowsExactlyMaxRange() {
        LocalDate to = KST_TODAY;
        LocalDate from = to.minusDays(MetricTrendService.MAX_RANGE_DAYS - 1L);
        when(metricPort.findBetween(from, to)).thenReturn(List.of());

        MetricTrend trend = service.view(range(from, to));

        assertThat(trend.from()).isEqualTo(from);
    }

    @Test
    @DisplayName("조회 기간을 그대로 포트에 넘긴다 — 어댑터가 날짜를 다시 계산하지 않는다")
    void passesResolvedRangeToPort() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        service.view(new TrendQuery(null, null, null));

        ArgumentCaptor<LocalDate> fromArg = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toArg = ArgumentCaptor.forClass(LocalDate.class);
        verify(metricPort).findBetween(fromArg.capture(), toArg.capture());
        assertThat(fromArg.getValue()).isEqualTo(KST_TODAY.minusDays(29));
        assertThat(toArg.getValue()).isEqualTo(KST_TODAY);
    }

    @Test
    @DisplayName("기간에 한 건도 없으면 asOf 는 null — 엉뚱한 시각을 찍지 않는다")
    void asOfNullWhenNothingStored() {
        when(metricPort.findBetween(any(), any())).thenReturn(List.of());

        assertThat(service.view(range(KST_TODAY, KST_TODAY)).asOf()).isNull();
    }

    @Test
    @DisplayName("asOf 는 기간 안에서 가장 늦게 갱신된 시각이다")
    void asOfIsLatestUpdate() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 21);
        when(metricPort.findBetween(from, to)).thenReturn(List.of(
                stored(from, DashboardMetric.ORDER_CREATED, 1, "10", 0),
                stored(to, DashboardMetric.ORDER_CREATED, 1, "10", 0)));

        MetricTrend trend = service.view(new TrendQuery(from, to,
                List.of(DashboardMetric.ORDER_CREATED.name())));

        assertThat(trend.asOf()).isEqualTo(to.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
