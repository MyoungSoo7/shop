package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.exception.UnknownEnumValueException;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase.CategoryBreakdown;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase.ProductRanking;
import github.lms.lemuel.order.application.port.in.ViewSalesStatsUseCase.SalesQuery;
import github.lms.lemuel.order.application.port.out.LoadSalesStatsPort;
import github.lms.lemuel.order.application.port.out.LoadSalesStatsPort.SalesCriteria;
import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.ProductSales;
import github.lms.lemuel.order.domain.SalesTotal;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SalesStatsService} 단위 테스트.
 *
 * <p>여기서 지키는 것은 SQL 이 아니라 <b>서비스가 어댑터에 무엇을 물어보는가</b>다. 기간과 상태가
 * 조용히 넓어지거나 좁아지는 것이 이 기능의 유일한 치명적 실패 모드라, 검증의 무게가 전부
 * {@link SalesCriteria} 에 실린다.
 *
 * <p>시계는 UTC 22:00 로 고정한다 — KST 로는 이미 <b>다음 날</b>이다. 서비스가 KST 시계를 쓰지
 * 않고 시스템 기본 타임존으로 "오늘"을 정하면 이 시각에 하루 어긋나고, 그 어긋남은 한국 시간
 * 자정~09시 사이에만 나타나 낮에 돌리는 테스트로는 절대 잡히지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class SalesStatsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-08-25 22:00 UTC = 2026-08-26 07:00 KST. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T22:00:00Z"), KST);

    private static final LocalDate KST_TODAY = LocalDate.of(2026, 8, 26);

    @Mock
    private LoadSalesStatsPort salesPort;

    private SalesStatsService service() {
        return new SalesStatsService(salesPort, CLOCK);
    }

    private SalesCriteria captureCriteria() {
        ArgumentCaptor<SalesCriteria> captor = ArgumentCaptor.forClass(SalesCriteria.class);
        verify(salesPort).topProducts(captor.capture(), anyInt());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- 기간

    @Test
    @DisplayName("종료일 기본값은 KST 오늘 — UTC 로 읽으면 하루 전이다")
    void 종료일은_KST_오늘() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        ProductRanking ranking = service().topProducts(new SalesQuery(null, null, null, null));

        assertThat(ranking.to()).isEqualTo(KST_TODAY);
    }

    @Test
    @DisplayName("기본 기간은 오늘을 포함한 30일 — 29일 전부터다")
    void 기본기간은_오늘포함_30일() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        ProductRanking ranking = service().topProducts(new SalesQuery(null, null, null, null));

        assertThat(ranking.from()).isEqualTo(KST_TODAY.minusDays(29));
        assertThat(ranking.to()).isEqualTo(KST_TODAY);
    }

    @Test
    @DisplayName("기간은 반열림으로 넘어간다 — 종료일 자정이 아니라 다음 날 자정")
    void 기간은_반열림() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null, null));

        SalesCriteria criteria = captureCriteria();
        assertThat(criteria.createdFrom()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        // 8월 7일 23:59:59.999 의 주문이 빠지지 않으려면 여기는 8월 8일 00:00 이어야 한다.
        assertThat(criteria.createdToExclusive()).isEqualTo(LocalDateTime.of(2026, 8, 8, 0, 0));
    }

    @Test
    @DisplayName("시작일만 주면 종료일은 오늘")
    void 시작일만_주면_종료일은_오늘() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        ProductRanking ranking = service().topProducts(
                new SalesQuery(LocalDate.of(2026, 1, 1), null, null, null));

        assertThat(ranking.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ranking.to()).isEqualTo(KST_TODAY);
    }

    @Test
    @DisplayName("종료일만 주면 그 날짜로부터 거꾸로 30일 — 오늘 기준이 아니다")
    void 종료일만_주면_거꾸로_30일() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        ProductRanking ranking = service().topProducts(
                new SalesQuery(null, LocalDate.of(2026, 3, 31), null, null));

        assertThat(ranking.from()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(ranking.to()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("뒤집힌 기간은 바로잡지 않고 거부한다 — 요청과 다른 기간의 매출이 보고서로 나간다")
    void 뒤집힌_기간은_거부() {
        assertThatThrownBy(() -> service().topProducts(new SalesQuery(
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최대 기간을 넘으면 거부한다 — 조용히 자르면 부분 집계를 전체로 오인한다")
    void 최대기간_초과는_거부() {
        LocalDate to = LocalDate.of(2026, 8, 26);
        LocalDate from = to.minusDays(SalesStatsService.MAX_RANGE_DAYS);   // 367일

        assertThatThrownBy(() -> service().topProducts(new SalesQuery(from, to, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정확히 최대 기간은 통과한다 — 경계에서 한 칸 어긋나면 366일 조회가 죽는다")
    void 정확히_최대기간은_통과() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        LocalDate to = LocalDate.of(2026, 8, 26);
        LocalDate from = to.minusDays(SalesStatsService.MAX_RANGE_DAYS - 1L);

        ProductRanking ranking = service().topProducts(new SalesQuery(from, to, null, null));

        assertThat(ranking.from()).isEqualTo(from);
    }

    @Test
    @DisplayName("같은 날 하루짜리 조회도 된다")
    void 하루짜리_조회() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        LocalDate day = LocalDate.of(2026, 8, 26);
        service().topProducts(new SalesQuery(day, day, null, null));

        SalesCriteria criteria = captureCriteria();
        assertThat(criteria.createdFrom()).isEqualTo(day.atStartOfDay());
        assertThat(criteria.createdToExclusive()).isEqualTo(day.plusDays(1).atStartOfDay());
    }

    // ---------------------------------------------------------------- 상태

    @Test
    @DisplayName("기본 상태는 '전부'가 아니다 — CREATED·CANCELED·REFUNDED 가 들어가면 매출이 부풀려진다")
    void 기본상태는_전부가_아니다() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(null, null, null, null));

        assertThat(captureCriteria().statuses())
                .containsExactly("PAID", "SHIPPING_PENDING", "IN_TRANSIT", "DELIVERED",
                        "CANCELLATION_REQUESTED", "REFUND_REQUESTED", "EXCHANGE_REQUESTED")
                .doesNotContain("CREATED", "CANCELLATION_APPROVED", "CANCELED",
                        "REFUNDED", "REFUND_COMPLETED");
    }

    @Test
    @DisplayName("빈 목록도 기본값으로 간다 — 상태 조건 없는 집계는 어댑터가 거부한다")
    void 빈_상태목록은_기본값() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(null, null, List.of(), null));

        assertThat(captureCriteria().statuses())
                .isEqualTo(SalesStatsService.DEFAULT_STATUSES.stream().map(Enum::name).toList());
    }

    @Test
    @DisplayName("상태 이름은 대소문자·공백을 정규화한다")
    void 상태이름_정규화() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(null, null, List.of(" paid ", "delivered"), null));

        assertThat(captureCriteria().statuses()).containsExactly("PAID", "DELIVERED");
    }

    @Test
    @DisplayName("중복 상태는 한 번만 — 같은 상태를 두 번 넣어도 IN 절만 길어진다")
    void 중복_상태는_제거() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(null, null, List.of("PAID", "paid", "PAID"), null));

        assertThat(captureCriteria().statuses()).containsExactly("PAID");
    }

    @Test
    @DisplayName("모르는 상태는 던진다 — 조용히 버리면 오타 하나가 '매출 0원'이 된다")
    void 모르는_상태는_거부() {
        assertThatThrownBy(() -> service().topProducts(
                new SalesQuery(null, null, List.of("PAYED"), null)))
                .isInstanceOf(UnknownEnumValueException.class);
    }

    @Test
    @DisplayName("종단 상태도 명시하면 셀 수 있다 — 기본에서 뺀 것이지 금지한 것이 아니다")
    void 종단상태도_명시하면_허용() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(null, null, List.of(OrderStatus.REFUNDED.name()), null));

        assertThat(captureCriteria().statuses()).containsExactly("REFUNDED");
    }

    // ---------------------------------------------------------------- limit

    @Test
    @DisplayName("limit 미지정은 기본값")
    void limit_기본값() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        ProductRanking ranking = service().topProducts(new SalesQuery(null, null, null, null));

        assertThat(ranking.limit()).isEqualTo(SalesStatsService.DEFAULT_LIMIT);
    }

    @Test
    @DisplayName("limit 상한을 서버가 건다 — 클라이언트가 지킬 약속으로 두면 안 된다")
    void limit_상한() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        ProductRanking ranking = service().topProducts(new SalesQuery(null, null, null, 1_000_000));

        assertThat(ranking.limit()).isEqualTo(SalesStatsService.MAX_LIMIT);
        verify(salesPort).topProducts(any(), eq(SalesStatsService.MAX_LIMIT));
    }

    @Test
    @DisplayName("0·음수 limit 은 기본값 — LIMIT 0 은 '빈 랭킹'이 되어 장사가 안 된 것처럼 보인다")
    void limit_0은_기본값() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        assertThat(service().topProducts(new SalesQuery(null, null, null, 0)).limit())
                .isEqualTo(SalesStatsService.DEFAULT_LIMIT);
        assertThat(service().topProducts(new SalesQuery(null, null, null, -5)).limit())
                .isEqualTo(SalesStatsService.DEFAULT_LIMIT);
    }

    // ---------------------------------------------------------------- 합계

    @Test
    @DisplayName("랭킹과 합계는 같은 조건 인스턴스를 쓴다 — 아니면 상위 N 의 합이 전체를 넘을 수 있다")
    void 랭킹과_합계는_같은_조건() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().topProducts(new SalesQuery(null, null, null, null));

        ArgumentCaptor<SalesCriteria> ranked = ArgumentCaptor.forClass(SalesCriteria.class);
        ArgumentCaptor<SalesCriteria> totaled = ArgumentCaptor.forClass(SalesCriteria.class);
        verify(salesPort).topProducts(ranked.capture(), anyInt());
        verify(salesPort).total(totaled.capture());

        assertThat(totaled.getValue()).isSameAs(ranked.getValue());
    }

    @Test
    @DisplayName("합계는 상위 N 의 합이 아니다 — 잘라내기 전 전 범위를 따로 센다")
    void 합계는_잘라내기_전_값() {
        when(salesPort.topProducts(any(), anyInt())).thenReturn(List.of(
                new ProductSales(1L, "가", 3, new BigDecimal("30000"), 3)));
        when(salesPort.total(any())).thenReturn(
                new SalesTotal(97, new BigDecimal("980000"), 60, 41));

        ProductRanking ranking = service().topProducts(new SalesQuery(null, null, null, 1));

        assertThat(ranking.rows()).hasSize(1);
        assertThat(ranking.total().netAmount()).isEqualByComparingTo("980000");
    }

    // ---------------------------------------------------------------- 카테고리

    @Test
    @DisplayName("카테고리 조회도 같은 기간·상태 규칙을 쓴다")
    void 카테고리도_같은_규칙() {
        when(salesPort.byCategory(any())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        CategoryBreakdown breakdown = service().byCategory(new SalesQuery(null, null, null, null));

        assertThat(breakdown.from()).isEqualTo(KST_TODAY.minusDays(29));
        assertThat(breakdown.to()).isEqualTo(KST_TODAY);
        assertThat(breakdown.statuses()).containsExactly("PAID", "SHIPPING_PENDING", "IN_TRANSIT",
                "DELIVERED", "CANCELLATION_REQUESTED", "REFUND_REQUESTED", "EXCHANGE_REQUESTED");
    }

    @Test
    @DisplayName("카테고리는 잘라내지 않는다 — limit 을 줘도 어댑터에 넘어가지 않는다")
    void 카테고리는_limit을_무시() {
        when(salesPort.byCategory(any())).thenReturn(List.of());
        when(salesPort.total(any())).thenReturn(SalesTotal.empty());

        service().byCategory(new SalesQuery(null, null, null, 5));

        verify(salesPort, times(1)).byCategory(any());
        verify(salesPort, never()).topProducts(any(), anyInt());
    }

    @Test
    @DisplayName("미분류 줄은 그대로 통과한다 — 서비스가 감추지 않는다")
    void 미분류_줄은_그대로() {
        CategorySales unclassified =
                new CategorySales(null, null, null, null, 4, new BigDecimal("40000"), 2);
        when(salesPort.byCategory(any())).thenReturn(List.of(
                new CategorySales(1L, "의류", "clothes", 0, 10, new BigDecimal("100000"), 7),
                unclassified));
        when(salesPort.total(any())).thenReturn(
                new SalesTotal(14, new BigDecimal("140000"), 12, 9));

        CategoryBreakdown breakdown = service().byCategory(new SalesQuery(null, null, null, null));

        assertThat(breakdown.rows()).contains(unclassified);
        assertThat(breakdown.rows().stream().filter(CategorySales::unclassified)).hasSize(1);
    }

    /**
     * 합계를 행에서 만들어 내지 않는지 본다.
     *
     * <p>행 합(100,000)과 전체 합(140,000)을 <b>일부러 어긋나게</b> 준다. 서비스가 행을 더해
     * 합계를 만들고 있었다면 100,000 이 나온다. 실제 운영에서 이 둘이 어긋나면 그건 SQL 이
     * 미분류를 떨어뜨렸거나 한 라인을 여러 분류로 중복 계산했다는 신호인데, 서비스가 행을
     * 더해 버리면 <b>항상 일치하는 것처럼 보여</b> 그 신호가 사라진다.
     */
    @Test
    @DisplayName("합계는 행에서 계산하지 않는다 — 그러면 SQL 의 누락·중복이 영영 안 보인다")
    void 합계는_행에서_만들지_않는다() {
        when(salesPort.byCategory(any())).thenReturn(List.of(
                new CategorySales(1L, "의류", "clothes", 0, 10, new BigDecimal("100000"), 7)));
        when(salesPort.total(any())).thenReturn(
                new SalesTotal(14, new BigDecimal("140000"), 12, 9));

        CategoryBreakdown breakdown = service().byCategory(new SalesQuery(null, null, null, null));

        assertThat(breakdown.total().netAmount()).isEqualByComparingTo("140000");
    }
}
