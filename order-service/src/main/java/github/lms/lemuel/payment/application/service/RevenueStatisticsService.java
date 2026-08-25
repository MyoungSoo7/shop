package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase;
import github.lms.lemuel.payment.application.port.out.LoadRevenueStatisticsPort;
import github.lms.lemuel.payment.application.port.out.LoadRevenueStatisticsPort.DailyAmount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기간 매출 집계.
 *
 * <p>하는 일은 셋이다 — 날짜 경계를 반개구간으로 정규화하고, 시간축이 다른 두 계열(수납·환불)을
 * 날짜로 맞물리고, 결제수단별 합계가 총액을 다 설명하는지 대조한다.
 */
@Service
public class RevenueStatisticsService implements ViewRevenueStatisticsUseCase {

    private final LoadRevenueStatisticsPort loadRevenueStatisticsPort;

    public RevenueStatisticsService(LoadRevenueStatisticsPort loadRevenueStatisticsPort) {
        this.loadRevenueStatisticsPort = loadRevenueStatisticsPort;
    }

    @Override
    @Transactional(readOnly = true)
    public RevenueReport report(RevenueQuery query) {
        // 화면이 말하는 "8/1 ~ 8/31" 은 31일을 포함한다. 종료일을 그대로 상한으로 쓰면 마지막
        // 하루가 통째로 빠지는데, 그 하루는 비어 보일 뿐 오류를 내지 않는다.
        LocalDateTime from = query.from().atStartOfDay();
        LocalDateTime toExclusive = query.toInclusive().plusDays(1).atStartOfDay();

        List<DailyAmount> captures = loadRevenueStatisticsPort.capturesByDay(from, toExclusive);
        List<DailyAmount> refunds = loadRevenueStatisticsPort.refundsByDay(from, toExclusive);
        List<TenderRevenue> byTender = loadRevenueStatisticsPort.capturedByTender(from, toExclusive);

        List<DailyRevenue> daily = merge(captures, refunds);

        BigDecimal capturedAmount = sum(captures);
        BigDecimal refundedAmount = sum(refunds);
        BigDecimal tenderAmount = byTender.stream()
                .map(TenderRevenue::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 분할결제 도입 전 결제는 수단 행이 없다. 그 차액을 0 으로 뭉개면 구성 비율만 그럴듯하게
        // 남고 합계가 총액에 못 미치는 것을 볼 방법이 사라진다. 음수는 만들지 않는다 —
        // 수단 합이 총액을 넘는 상황은 데이터가 깨졌다는 뜻이지 "음수 미상"이 아니다.
        BigDecimal unattributed = capturedAmount.subtract(tenderAmount).max(BigDecimal.ZERO);

        return new RevenueReport(daily, byTender, capturedAmount, refundedAmount, unattributed);
    }

    /**
     * 두 계열을 날짜로 맞물린다.
     *
     * <p>양쪽 다 없는 날은 행을 만들지 않는다. 빈 날을 0 으로 채우면 "집계가 안 돌았다"와
     * "그날 아무 일도 없었다"가 같은 모양이 된다 — 앞의 경우를 영영 눈치채지 못한다.
     */
    private static List<DailyRevenue> merge(List<DailyAmount> captures, List<DailyAmount> refunds) {
        Map<LocalDate, DailyAmount> refundByDate = new LinkedHashMap<>();
        for (DailyAmount r : refunds) {
            refundByDate.put(r.date(), r);
        }

        List<DailyRevenue> merged = new ArrayList<>();
        for (DailyAmount c : captures) {
            DailyAmount r = refundByDate.remove(c.date());
            merged.add(new DailyRevenue(c.date(), c.count(), c.amount(),
                    r == null ? 0L : r.count(),
                    r == null ? BigDecimal.ZERO : r.amount()));
        }
        // 수납 없이 환불만 있던 날 — 순매출이 음수인 날이다. 빠뜨리면 기간 합계와 일자 합계가
        // 어긋나는데, 어긋난 쪽이 화면이라 조용하다.
        for (DailyAmount r : refundByDate.values()) {
            merged.add(new DailyRevenue(r.date(), 0L, BigDecimal.ZERO, r.count(), r.amount()));
        }

        merged.sort(java.util.Comparator.comparing(DailyRevenue::date));
        return merged;
    }

    private static BigDecimal sum(List<DailyAmount> rows) {
        return rows.stream().map(DailyAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
