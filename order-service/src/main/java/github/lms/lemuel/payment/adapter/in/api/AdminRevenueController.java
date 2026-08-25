package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase;
import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.RevenueQuery;
import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.RevenueReport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 매출 콘솔 — 기간 매출 추이와 결제수단별 구성.
 *
 * <pre>
 *   GET /admin/revenue?from=2026-08-01&amp;to=2026-08-31
 * </pre>
 *
 * <p>기존 대시보드의 "총 매출"이 <b>현재 상태가 PAID 인 주문의 주문금액 합</b>이라 발송된 주문이
 * 매출에서 빠지고 환불이 차감되지 않는 문제를 대신한다. 정의는
 * {@link ViewRevenueStatisticsUseCase} 에 적혀 있다.
 *
 * <p>권한은 {@code SecurityConfig} 의 {@code /admin/revenue/**} 매처(ADMIN/MANAGER)로 제한된다.
 * 이 저장소에는 {@code @EnableMethodSecurity} 가 없어 {@code @PreAuthorize} 가 동작하지 않으므로,
 * <b>URL 매처가 유일한 인가 수단</b>이다. 매처를 빠뜨리면 로그인한 아무나 매출 전체를 본다.
 */
@RestController
@RequestMapping("/admin/revenue")
public class AdminRevenueController {

    /** 기간을 안 주고 부를 때의 폭 — 오늘 포함 최근 30일. */
    private static final int DEFAULT_DAYS = 30;

    private final ViewRevenueStatisticsUseCase viewRevenueStatisticsUseCase;

    public AdminRevenueController(ViewRevenueStatisticsUseCase viewRevenueStatisticsUseCase) {
        this.viewRevenueStatisticsUseCase = viewRevenueStatisticsUseCase;
    }

    /**
     * @param from 시작일(포함). 생략하면 {@code to} 기준 최근 {@value #DEFAULT_DAYS}일
     * @param to   종료일(<b>포함</b>). 생략하면 오늘
     */
    @GetMapping
    public ResponseEntity<RevenueResponse> report(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate toInclusive = to != null ? to : LocalDate.now();
        LocalDate fromDate = from != null ? from : toInclusive.minusDays(DEFAULT_DAYS - 1L);

        RevenueReport report = viewRevenueStatisticsUseCase.report(new RevenueQuery(fromDate, toInclusive));
        return ResponseEntity.ok(RevenueResponse.from(fromDate, toInclusive, report));
    }

    /**
     * 응답.
     *
     * <p>{@code netAmount} 를 서버가 실어 보낸다. 화면이 빼기를 하게 두면 <b>어느 화면은 환불을
     * 빼고 어느 화면은 안 빼는</b> 상태가 생기고, 그 둘 다 그럴듯한 숫자라 아무도 눈치채지 못한다.
     *
     * @param tenderBreakdownComplete 결제수단별 합계가 총 수납액을 전부 설명하는가.
     *                                {@code false} 면 화면은 반드시 "수단 미상"을 함께 보여야 한다 —
     *                                구성 비율만 그리면 합이 총액에 못 미치는 것을 볼 사람이 없다
     */
    public record RevenueResponse(
            LocalDate from,
            LocalDate to,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            BigDecimal netAmount,
            BigDecimal unattributedAmount,
            boolean tenderBreakdownComplete,
            List<DailyItem> daily,
            List<TenderItem> byTender) {

        static RevenueResponse from(LocalDate from, LocalDate to, RevenueReport report) {
            return new RevenueResponse(
                    from, to,
                    report.capturedAmount(),
                    report.refundedAmount(),
                    report.netAmount(),
                    report.unattributedAmount(),
                    report.tenderBreakdownIsComplete(),
                    report.daily().stream()
                            .map(d -> new DailyItem(d.date(), d.capturedCount(), d.capturedAmount(),
                                    d.refundCount(), d.refundedAmount(), d.netAmount()))
                            .toList(),
                    report.byTender().stream()
                            .map(t -> new TenderItem(t.tenderType().name(), t.usesExternalPg(),
                                    t.count(), t.amount()))
                            .toList());
        }
    }

    /**
     * 하루치.
     *
     * <p>수납도 환불도 없던 날은 <b>행이 없다</b>. 0 으로 채우는 것은 화면의 몫이다 — 서버가 채우면
     * "집계가 안 돌았다"와 "그날 장사가 없었다"가 같은 모양이 된다.
     */
    public record DailyItem(
            LocalDate date,
            long capturedCount,
            BigDecimal capturedAmount,
            long refundCount,
            BigDecimal refundedAmount,
            BigDecimal netAmount) {}

    /**
     * 결제수단 한 칸.
     *
     * @param usesExternalPg 외부 PG 로 실제 돈이 들어왔는가. POINT·GIFT_CARD 는 내부 잔액 차감이라
     *                       이 기간에 새로 들어온 현금이 아니다 — 상품권은 팔릴 때 이미 한 번
     *                       수납됐다. 화면이 이 축으로 갈라 볼 수 있게 함께 내려보낸다
     */
    public record TenderItem(
            String tenderType,
            boolean usesExternalPg,
            long count,
            BigDecimal amount) {}
}
