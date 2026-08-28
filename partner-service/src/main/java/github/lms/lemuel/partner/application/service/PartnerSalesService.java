package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.dto.PartnerDashboardView;
import github.lms.lemuel.partner.application.port.in.ViewPartnerSalesUseCase;
import github.lms.lemuel.partner.application.port.out.PartnerSalesQueryPort;
import github.lms.lemuel.partner.domain.PartnerScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 대시보드 조립.
 *
 * <p>기간 상한({@link #MAX_RANGE_DAYS})이 있는 이유는 레퍼런스의 실패에서 왔다 — 기간을 열어
 * 두면 "전체" 를 고른 한 번의 조회가 백오피스 전체를 멎게 한다. 상한을 넘으면 조용히 자르지 않고
 * 거절한다. 조용히 자르면 사용자는 자신이 고른 기간의 합계를 보고 있다고 믿는다.
 */
@Service
@Transactional(readOnly = true)
public class PartnerSalesService implements ViewPartnerSalesUseCase {

    /** 1년 + 윤년 여유. 그 이상은 정산 화면이 아니라 데이터 추출의 영역이다. */
    public static final int MAX_RANGE_DAYS = 366;
    private static final int BEST_PRODUCT_LIMIT = 10;
    private static final int DEFAULT_DAYS = 30;

    private final PartnerSalesQueryPort salesQueryPort;
    private final Clock clock;

    public PartnerSalesService(PartnerSalesQueryPort salesQueryPort, Clock clock) {
        this.salesQueryPort = salesQueryPort;
        this.clock = clock;
    }

    @Override
    public PartnerDashboardView dashboard(PartnerScope scope, LocalDate from, LocalDate to) {
        long sellerId = scope.requireSellerId();

        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("시작일이 종료일보다 늦습니다: " + start + " ~ " + end);
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "조회 기간은 최대 " + MAX_RANGE_DAYS + "일입니다 (요청 " + days + "일).");
        }

        return new PartnerDashboardView(
                start,
                end,
                salesQueryPort.summary(sellerId, start, end),
                salesQueryPort.daily(sellerId, start, end),
                salesQueryPort.bestProducts(sellerId, start, end, BEST_PRODUCT_LIMIT),
                salesQueryPort.hasEstimatedCaptureDates(sellerId, start, end));
    }
}
