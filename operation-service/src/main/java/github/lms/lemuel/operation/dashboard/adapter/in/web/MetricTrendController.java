package github.lms.lemuel.operation.dashboard.adapter.in.web;

import github.lms.lemuel.operation.dashboard.application.port.in.ViewMetricTrendUseCase;
import github.lms.lemuel.operation.dashboard.application.port.in.ViewMetricTrendUseCase.TrendQuery;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.MetricTrend;
import github.lms.lemuel.operation.dashboard.domain.MetricTrend.MetricTotal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 지표 추이 — 날짜별 계열 한 번의 호출.
 *
 * <pre>
 *   GET /api/ops/dashboard/trend                              → 최근 30일 · 전 지표
 *   GET /api/ops/dashboard/trend?from=…&amp;to=…                 → 기간 지정(양끝 포함)
 *   GET /api/ops/dashboard/trend?metric=ORDER_CREATED&amp;…       → 지표 좁히기(반복 가능)
 * </pre>
 *
 * <p><b>왜 생기는가</b>: {@code ops_daily_metric} 은 날짜별로 계속 쌓이는데 읽는 경로가
 * {@code /today} 하나뿐이었다. 하루씩만 꺼낼 수 있으니 30일 그래프를 그리려면 화면이 같은
 * 엔드포인트를 30번 불러야 했고, 그래서 아무도 안 그렸다. 새 집계를 만드는 게 아니라
 * <b>이미 매일 쌓이고 있던 값을 처음으로 꺼내는</b> 것이다 — 스키마 변경도 없다.
 *
 * <p><b>{@code TodayOverviewController} 와 나눈 이유</b>: 그쪽은 하루를 세로로 훑어 인시던트·
 * 발송실패까지 함께 보는 운영 상태 화면이다. 이름이 "오늘"인 클래스에 기간 조회를 밀어 넣으면
 * 다음 사람이 그 파일에서 기간 로직을 찾지 못한다.
 *
 * <p><b>권한</b>: {@code OperationSecurityConfig} 가 {@code securityMatcher("/api/ops/**")} 아래
 * {@code anyRequest().hasRole("ADMIN")} 으로 덮으므로 이 경로는 <b>기본으로 ADMIN 전용</b>이다.
 * order-service 의 {@code SecurityConfig}(경로를 하나씩 열거하고 빠뜨리면 새는 구조)와 성질이
 * 반대라, 여기서는 매처를 새로 추가하지 않는 것이 맞다.
 */
@Tag(name = "Ops Dashboard", description = "운영 대시보드 일별 요약(ADMIN)")
@RestController
@RequestMapping("/api/ops/dashboard")
public class MetricTrendController {

    private final ViewMetricTrendUseCase viewMetricTrendUseCase;

    public MetricTrendController(ViewMetricTrendUseCase viewMetricTrendUseCase) {
        this.viewMetricTrendUseCase = viewMetricTrendUseCase;
    }

    /**
     * @param metric 반복 가능. 생략하면 전 지표. 모르는 이름은 400 이다 —
     *               조용히 버리면 화면이 빠진 줄 모르고 남은 것만 그린다.
     */
    @GetMapping("/trend")
    @Operation(summary = "지표 추이",
            description = "날짜별 건수·금액 계열과 기간 합계. 값이 없는 날은 0 으로 채워 보낸다. 기본 최근 30일, 최대 366일")
    public ResponseEntity<MetricTrendResponse> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> metric) {

        return ResponseEntity.ok(
                MetricTrendResponse.from(viewMetricTrendUseCase.view(new TrendQuery(from, to, metric))));
    }

    /**
     * 화면이 받는 모양.
     *
     * @param asOf 집계에 반영된 마지막 이벤트 시각. 기간 안에 한 건도 없으면 {@code null} 이며,
     *            그때 화면은 시각 대신 "아직 없음"을 그려야 한다 — 없는 기간에 엉뚱한 시각을
     *            찍는 것이 가장 나쁘다.
     */
    public record MetricTrendResponse(
            LocalDate from,
            LocalDate to,
            String zone,
            Instant asOf,
            List<String> metrics,
            List<TrendPoint> series,
            List<TrendTotal> totals) {

        static MetricTrendResponse from(MetricTrend trend) {
            return new MetricTrendResponse(
                    trend.from(),
                    trend.to(),
                    trend.zone(),
                    trend.asOf(),
                    trend.metrics().stream().map(Enum::name).toList(),
                    trend.series().stream().map(TrendPoint::from).toList(),
                    trend.totals().stream().map(TrendTotal::from).toList());
        }
    }

    /**
     * 계열의 점 하나.
     *
     * <p>{@code label}·{@code hasAmount} 를 점마다 되풀이하지 않는 이유는 그 둘이 날짜가 아니라
     * 지표의 성질이기 때문이다. 필요한 화면은 {@link TrendTotal} 에서 한 번만 읽으면 된다 —
     * 같은 문자열을 366번 실어 보내면 응답이 지표 개수만큼 그냥 커진다.
     *
     * @param amountComplete 이 날짜의 모든 이벤트에서 금액을 읽었는지. 거짓이면 {@code amount} 는
     *                       <b>하한</b>이며 화면이 "일부 미상"을 붙여야 한다
     */
    public record TrendPoint(
            LocalDate date,
            String metric,
            long count,
            BigDecimal amount,
            boolean amountComplete,
            long amountUnknownCount) {

        static TrendPoint from(DailyMetric metric) {
            return new TrendPoint(
                    metric.date(),
                    metric.metric().name(),
                    metric.eventCount(),
                    metric.metric().hasAmount() ? metric.amountSum() : null,
                    metric.amountComplete(),
                    metric.amountUnknownCount());
        }
    }

    /**
     * 기간 합계 한 줄.
     *
     * <p>한글 표기({@code label})는 서버가 정한다 — {@code TodayOverviewController.MetricCard} 와
     * 같은 이유다. 화면에 매핑 테이블을 두면 지표를 더할 때 그 표를 고치는 걸 잊은 만큼
     * 키가 그대로 노출된 카드가 뜬다.
     */
    public record TrendTotal(
            String metric,
            String label,
            long count,
            BigDecimal amount,
            boolean hasAmount,
            boolean amountComplete,
            long amountUnknownCount) {

        static TrendTotal from(MetricTotal total) {
            return new TrendTotal(
                    total.metric().name(),
                    total.metric().label(),
                    total.eventCount(),
                    total.metric().hasAmount() ? total.amountSum() : null,
                    total.metric().hasAmount(),
                    total.amountComplete(),
                    total.amountUnknownCount());
        }
    }
}
