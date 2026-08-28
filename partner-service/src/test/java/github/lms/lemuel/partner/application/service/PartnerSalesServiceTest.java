package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.dto.BestProductView;
import github.lms.lemuel.partner.application.port.dto.DailySalesView;
import github.lms.lemuel.partner.application.port.dto.PartnerDashboardView;
import github.lms.lemuel.partner.application.port.dto.SalesSummaryView;
import github.lms.lemuel.partner.application.port.out.PartnerSalesQueryPort;
import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;
import github.lms.lemuel.partner.domain.PartnerScope;
import github.lms.lemuel.partner.domain.exception.NoSalesScopeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 대시보드 조립. 로직은 <b>기간을 정하는 것</b>과 <b>그 기간을 거절하는 것</b> 둘뿐이다.
 *
 * <p>상한이 있는 이유는 레퍼런스의 실패에서 왔다 — 기간을 열어 두면 "전체" 를 고른 한 번의
 * 조회가 백오피스 전체를 멎게 했다. 그리고 상한을 넘을 때 <b>조용히 자르지 않고 거절</b>하는
 * 것이 이 클래스의 성격이다. 조용히 자르면 사용자는 자기가 고른 기간의 합계를 보고 있다고 믿는다.
 */
class PartnerSalesServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);
    private static final SalesSummaryView SUMMARY =
            new SalesSummaryView(new BigDecimal("50000"), new BigDecimal("10000"), new BigDecimal("40000"), 3L);

    private PartnerSalesQueryPort queryPort;
    private PartnerSalesService service;

    @BeforeEach
    void setUp() {
        queryPort = mock(PartnerSalesQueryPort.class);
        service = new PartnerSalesService(queryPort,
                Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST));
    }

    private static PartnerScope seller() {
        return new PartnerScope(7L, "명수상사", OrgType.SELLER, 777L, MemberRole.OWNER);
    }

    private static PartnerScope corporate() {
        return new PartnerScope(9L, "르무엘법인", OrgType.CORPORATE, null, MemberRole.STAFF);
    }

    @Test
    void 기간을_안_주면_오늘까지_최근_30일이다() {
        LocalDate start = LocalDate.of(2026, 7, 31);
        when(queryPort.summary(777L, start, TODAY)).thenReturn(SUMMARY);

        PartnerDashboardView view = service.dashboard(seller(), null, null);

        // 30일 = 오늘 포함. 29일을 빼야 30일이다 — 30 을 빼면 하루가 더 들어간다.
        assertEquals(start, view.from());
        assertEquals(TODAY, view.to());
        assertEquals(SUMMARY, view.summary());
    }

    @Test
    void 종료일만_주면_그날까지_30일이다() {
        LocalDate end = LocalDate.of(2026, 6, 30);
        PartnerDashboardView view = service.dashboard(seller(), null, end);

        assertEquals(LocalDate.of(2026, 6, 1), view.from());
        assertEquals(end, view.to());
    }

    @Test
    void 시작일만_주면_오늘까지다() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        PartnerDashboardView view = service.dashboard(seller(), start, null);

        assertEquals(start, view.from());
        assertEquals(TODAY, view.to());
    }

    @Test
    void 네_조회를_같은_기간_같은_셀러로_한_번에_묶는다() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        List<DailySalesView> daily = List.of(new DailySalesView(from,
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"), 1L));
        List<BestProductView> best = List.of(
                new BestProductView(11L, "텀블러", new BigDecimal("40000"), 2L));
        when(queryPort.summary(777L, from, TODAY)).thenReturn(SUMMARY);
        when(queryPort.daily(777L, from, TODAY)).thenReturn(daily);
        when(queryPort.bestProducts(777L, from, TODAY, 10)).thenReturn(best);
        when(queryPort.hasEstimatedCaptureDates(777L, from, TODAY)).thenReturn(true);

        PartnerDashboardView view = service.dashboard(seller(), from, TODAY);

        // 따로 부르면 그 사이 들어온 이벤트 때문에 합계와 일자별 합이 어긋나고, 사용자는
        // 그걸 버그로 신고한다. 한 응답으로 묶는 것이 이 DTO 의 존재 이유다.
        assertEquals(SUMMARY, view.summary());
        assertEquals(daily, view.daily());
        assertEquals(best, view.bestProducts());
        assertTrue(view.estimatedCaptureDates());
        verify(queryPort).bestProducts(777L, from, TODAY, 10);
    }

    @Test
    void 기간이_뒤집히면_조용히_바꾸지_않고_거절한다() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.dashboard(seller(), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1)));

        assertTrue(thrown.getMessage().contains("시작일이 종료일보다"), thrown.getMessage());
        verifyNoInteractions(queryPort);
    }

    @Test
    void 하루짜리_기간은_정상이다() {
        service.dashboard(seller(), TODAY, TODAY);

        verify(queryPort).summary(777L, TODAY, TODAY);
    }

    @Test
    void 상한_366일_까지는_통과한다() {
        LocalDate from = TODAY.minusDays(365);   // 양끝 포함 366일

        PartnerDashboardView view = service.dashboard(seller(), from, TODAY);

        assertEquals(from, view.from());
        verify(queryPort).summary(777L, from, TODAY);
    }

    @Test
    void 상한을_하루_넘기면_거절하고_몇_일인지_알려_준다() {
        LocalDate from = TODAY.minusDays(366);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.dashboard(seller(), from, TODAY));

        // 거절만 하고 끝내면 사용자는 얼마를 줄여야 하는지 모른다. 요청 일수를 함께 적는다.
        assertTrue(thrown.getMessage().contains("최대 366일"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("367일"), thrown.getMessage());
        verifyNoInteractions(queryPort);
    }

    @Test
    void 매출_스코프가_없으면_조회를_아예_시작하지_않는다() {
        assertThrows(NoSalesScopeException.class,
                () -> service.dashboard(corporate(), null, null));

        // 첫 줄에서 막히는 것이 요점이다. 한 번이라도 포트를 타면 그 쿼리의 조건은
        // seller_id IS NULL 이 되고, 셀러 미할당 결제 전체가 이 법인에 보인다.
        verifyNoInteractions(queryPort);
    }
}
