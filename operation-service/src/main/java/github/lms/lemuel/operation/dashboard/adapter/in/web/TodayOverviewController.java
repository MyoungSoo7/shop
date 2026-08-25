package github.lms.lemuel.operation.dashboard.adapter.in.web;

import github.lms.lemuel.operation.dashboard.application.port.in.ViewTodayOverviewUseCase;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.TodayOverview;
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
 * "오늘 한눈에" — 하루치 운영 요약 한 번의 호출.
 *
 * <pre>
 *   GET /api/ops/dashboard/today          → 오늘(KST)
 *   GET /api/ops/dashboard/today?date=…   → 특정 날짜(어제와 비교하거나 사고 당일을 되짚을 때)
 * </pre>
 *
 * <p><b>왜 이게 생기는가</b>: 관리자 대시보드의 개요 탭은 이미 있었지만, 숫자를 만드는 방법이
 * 주문 전건·회원 전건·상품 전건·쿠폰 전건을 브라우저로 내려받아 {@code reduce()} 하는 것이었다.
 * 세 가지가 동시에 잘못돼 있다 — (1) 데이터가 늘면 화면이 느려지다 결국 죽고, (2) 카드 몇 개를
 * 보려고 <b>전 회원의 이메일</b>이 브라우저로 내려오며, (3) 그렇게 얻은 값은 '오늘'이 아니라
 * 전 기간 누계다. 서비스 간 조인을 화면에서 하던 옛 구조가 클라이언트로 자리만 옮긴 셈이다.
 *
 * <p>그래서 응답은 <b>카드에 찍히는 숫자만</b> 담는다. 원본 목록은 한 줄도 나가지 않는다.
 *
 * <p>권한은 {@code OperationSecurityConfig} 의 {@code /api/ops/**} 체인이 ROLE_ADMIN 으로 막는다.
 */
@Tag(name = "Ops Dashboard", description = "운영 대시보드 일별 요약(ADMIN)")
@RestController
@RequestMapping("/api/ops/dashboard")
public class TodayOverviewController {

    private final ViewTodayOverviewUseCase viewTodayOverviewUseCase;

    public TodayOverviewController(ViewTodayOverviewUseCase viewTodayOverviewUseCase) {
        this.viewTodayOverviewUseCase = viewTodayOverviewUseCase;
    }

    @GetMapping("/today")
    @Operation(summary = "오늘 한눈에", description = "일별 집계·미해결 인시던트·오늘 실패 알림을 한 번에")
    public ResponseEntity<TodayOverviewResponse> today(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        TodayOverview overview = date == null
                ? viewTodayOverviewUseCase.today()
                : viewTodayOverviewUseCase.onDate(date);

        return ResponseEntity.ok(TodayOverviewResponse.from(overview));
    }

    /**
     * 화면이 받는 모양.
     *
     * @param asOf 집계에 반영된 마지막 이벤트 시각. 이벤트로 채워지는 화면은 <b>항상 조금 늦다</b>.
     *             그 지연을 숨기면 방금 들어온 주문이 안 보일 때 사람이 시스템을 의심하게 되므로,
     *             화면에 "○시 ○분 기준"으로 같이 찍으라고 내보낸다. 오늘 이벤트가 하나도 없으면
     *             {@code null} 이다 — 어제 시각을 대신 보여 주면 그게 제일 나쁜 거짓말이다.
     */
    public record TodayOverviewResponse(
            LocalDate date,
            String zone,
            Instant asOf,
            List<MetricCard> metrics,
            long openIncidents,
            long failedDispatches) {

        static TodayOverviewResponse from(TodayOverview overview) {
            return new TodayOverviewResponse(
                    overview.date(),
                    overview.zone(),
                    overview.asOf(),
                    overview.metrics().stream().map(MetricCard::from).toList(),
                    overview.openIncidents(),
                    overview.failedDispatches());
        }
    }

    /**
     * 카드 한 장.
     *
     * @param label          한글 표기는 서버가 정한다 — 지표를 더할 때 화면 코드에 매핑 테이블을
     *                       하나 더 고쳐야 하면, 언젠가 키가 그대로 노출된 카드가 뜬다.
     * @param hasAmount      금액 칸을 그릴 지표인지. 가입 건수처럼 금액이 없는 지표에 "0원"을
     *                       찍으면 매출이 0인 것처럼 읽힌다.
     * @param amountComplete 이 날짜의 모든 이벤트에서 금액을 읽었는지. 거짓이면 화면이 합계 옆에
     *                       "일부 미상"을 붙인다 — 모르는 값을 조용히 0으로 합산하지 않는다.
     */
    public record MetricCard(
            String key,
            String label,
            long count,
            BigDecimal amount,
            boolean hasAmount,
            boolean amountComplete,
            long amountUnknownCount) {

        static MetricCard from(DailyMetric metric) {
            return new MetricCard(
                    metric.metric().name(),
                    metric.metric().label(),
                    metric.eventCount(),
                    metric.metric().hasAmount() ? metric.amountSum() : null,
                    metric.metric().hasAmount(),
                    metric.amountComplete(),
                    metric.amountUnknownCount());
        }
    }
}
