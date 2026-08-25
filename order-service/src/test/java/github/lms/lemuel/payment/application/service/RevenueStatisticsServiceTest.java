package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.DailyRevenue;
import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.RevenueQuery;
import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.RevenueReport;
import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.TenderRevenue;
import github.lms.lemuel.payment.application.port.out.LoadRevenueStatisticsPort;
import github.lms.lemuel.payment.application.port.out.LoadRevenueStatisticsPort.DailyAmount;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기간 매출 집계 — 경계·병합·대조.
 *
 * <p>SQL 이 맞는지는 여기서 볼 수 없다(그건 통합테스트의 몫이다). 여기서 지키는 것은 그 위에
 * 얹힌 셋 — 날짜 경계를 반개구간으로 옮기는 규칙, 시간축이 다른 두 계열을 날짜로 맞물리는 규칙,
 * 결제수단 합계가 총액을 다 설명하는지 대조하는 규칙이다.
 */
class RevenueStatisticsServiceTest {

    private LoadRevenueStatisticsPort port;
    private RevenueStatisticsService service;

    @BeforeEach
    void setUp() {
        port = mock(LoadRevenueStatisticsPort.class);
        service = new RevenueStatisticsService(port);
        // 명시하지 않은 계열은 빈 결과 — 각 테스트가 보려는 축만 채운다.
        when(port.capturesByDay(any(), any())).thenReturn(List.of());
        when(port.refundsByDay(any(), any())).thenReturn(List.of());
        when(port.capturedByTender(any(), any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("기간 경계")
    class Boundary {

        /**
         * 화면이 "8월 1일 ~ 8월 31일"이라고 말할 때 31일은 <b>포함</b>이다. 종료일을 그대로 상한으로
         * 넘기면 마지막 하루가 통째로 빠지는데, 그 하루는 비어 보일 뿐 오류를 내지 않는다 —
         * 월말 매출이 하루치 모자란 채로 계속 보고된다.
         */
        @Test
        @DisplayName("종료일은 포함이므로 상한은 그 다음 날 0시다")
        void 종료일_포함() {
            service.report(new RevenueQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));

            verify(port).capturesByDay(
                    LocalDateTime.of(2026, 8, 1, 0, 0),
                    LocalDateTime.of(2026, 9, 1, 0, 0));
        }

        @Test
        @DisplayName("하루짜리 조회도 그 하루를 다 덮는다")
        void 하루_조회() {
            LocalDate day = LocalDate.of(2026, 8, 26);
            service.report(new RevenueQuery(day, day));

            verify(port).refundsByDay(
                    LocalDateTime.of(2026, 8, 26, 0, 0),
                    LocalDateTime.of(2026, 8, 27, 0, 0));
        }

        @Test
        @DisplayName("세 계열 모두 같은 기간을 받는다")
        void 세_계열_같은_기간() {
            LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
            LocalDateTime toExclusive = LocalDateTime.of(2026, 8, 11, 0, 0);

            service.report(new RevenueQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10)));

            verify(port).capturesByDay(from, toExclusive);
            verify(port).refundsByDay(from, toExclusive);
            verify(port).capturedByTender(from, toExclusive);
        }
    }

    @Nested
    @DisplayName("조회 기간 검증")
    class QueryValidation {

        @Test
        @DisplayName("기간은 필수")
        void 널은_거부() {
            assertThatThrownBy(() -> new RevenueQuery(null, LocalDate.of(2026, 8, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RevenueQuery(LocalDate.of(2026, 8, 1), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("역전된 기간은 거부 — 조용히 빈 결과로 두면 '매출 0' 으로 읽힌다")
        void 역전_기간은_거부() {
            assertThatThrownBy(() -> new RevenueQuery(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("종료일");
        }

        /** 일자 행을 그대로 응답에 싣기 때문에 상한이 필요하다 — 경계는 <b>포함</b>이라 366일까지 된다. */
        @Test
        @DisplayName("최대 366일까지 허용하고 하루라도 넘으면 거부")
        void 상한_경계() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            assertThatCode(() -> new RevenueQuery(from, from.plusDays(365)))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> new RevenueQuery(from, from.plusDays(366)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("366");
        }
    }

    @Nested
    @DisplayName("일자별 병합")
    class DailyMerge {

        @Test
        @DisplayName("같은 날의 수납과 환불은 한 줄로 합쳐진다")
        void 같은_날_합쳐진다() {
            LocalDate day = LocalDate.of(2026, 8, 10);
            when(port.capturesByDay(any(), any()))
                    .thenReturn(List.of(new DailyAmount(day, 3L, new BigDecimal("30000"))));
            when(port.refundsByDay(any(), any()))
                    .thenReturn(List.of(new DailyAmount(day, 1L, new BigDecimal("10000"))));

            RevenueReport report = report10Days();

            assertThat(report.daily()).singleElement().satisfies(d -> {
                assertThat(d.capturedCount()).isEqualTo(3L);
                assertThat(d.refundCount()).isEqualTo(1L);
                assertThat(d.netAmount()).isEqualByComparingTo("20000");
            });
        }

        /**
         * 환불만 있는 날은 <b>순매출이 음수</b>다. 이 줄을 빠뜨리면 기간 합계와 일자 합계가
         * 어긋나는데, 어긋난 쪽이 화면이라 조용하다.
         */
        @Test
        @DisplayName("수납 없이 환불만 있는 날도 줄이 선다 — 그날 순매출은 음수다")
        void 환불만_있는_날() {
            LocalDate day = LocalDate.of(2026, 8, 12);
            when(port.refundsByDay(any(), any()))
                    .thenReturn(List.of(new DailyAmount(day, 2L, new BigDecimal("15000"))));

            RevenueReport report = report10Days();

            assertThat(report.daily()).singleElement().satisfies(d -> {
                assertThat(d.date()).isEqualTo(day);
                assertThat(d.capturedCount()).isZero();
                assertThat(d.capturedAmount()).isEqualByComparingTo("0");
                assertThat(d.netAmount()).isNegative();
            });
            assertThat(report.netAmount()).isEqualByComparingTo("-15000");
        }

        @Test
        @DisplayName("두 계열이 섞여도 날짜순으로 정렬된다")
        void 날짜순_정렬() {
            when(port.capturesByDay(any(), any())).thenReturn(List.of(
                    new DailyAmount(LocalDate.of(2026, 8, 3), 1L, new BigDecimal("1000")),
                    new DailyAmount(LocalDate.of(2026, 8, 7), 1L, new BigDecimal("1000"))));
            when(port.refundsByDay(any(), any())).thenReturn(List.of(
                    new DailyAmount(LocalDate.of(2026, 8, 1), 1L, new BigDecimal("500")),
                    new DailyAmount(LocalDate.of(2026, 8, 5), 1L, new BigDecimal("500"))));

            RevenueReport report = report10Days();

            assertThat(report.daily()).extracting(DailyRevenue::date).containsExactly(
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 3),
                    LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 8, 7));
        }

        /**
         * 빈 날을 0 으로 채우지 <b>않는다</b>. 채우면 "집계가 안 돌았다"와 "그날 장사가 없었다"가
         * 같은 모양이 돼 앞의 경우를 영영 눈치채지 못한다. 0 을 그리는 것은 화면의 몫이다.
         */
        @Test
        @DisplayName("아무 일도 없던 날은 행을 만들지 않는다")
        void 빈_날은_행이_없다() {
            when(port.capturesByDay(any(), any())).thenReturn(List.of(
                    new DailyAmount(LocalDate.of(2026, 8, 1), 1L, new BigDecimal("1000")),
                    new DailyAmount(LocalDate.of(2026, 8, 10), 1L, new BigDecimal("1000"))));

            assertThat(report10Days().daily()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("결제수단 대조")
    class TenderReconciliation {

        /**
         * 분할결제 도입 전 결제는 tender 행이 없다. 그 차액을 0 으로 뭉개면 구성 비율만 그럴듯하게
         * 남고 <b>합계가 총액에 못 미치는 것을 볼 방법이 사라진다</b>.
         */
        @Test
        @DisplayName("수단 행이 없는 옛 결제는 '수단 미상'으로 드러난다")
        void 수단_미상() {
            when(port.capturesByDay(any(), any())).thenReturn(List.of(
                    new DailyAmount(LocalDate.of(2026, 8, 1), 2L, new BigDecimal("50000"))));
            when(port.capturedByTender(any(), any())).thenReturn(List.of(
                    new TenderRevenue(TenderType.CARD, true, 1L, new BigDecimal("30000"))));

            RevenueReport report = report10Days();

            assertThat(report.unattributedAmount()).isEqualByComparingTo("20000");
            assertThat(report.tenderBreakdownIsComplete()).isFalse();
        }

        @Test
        @DisplayName("수단 합이 총액과 맞으면 미상은 0 이고 '완전'으로 표시된다")
        void 완전한_구성() {
            when(port.capturesByDay(any(), any())).thenReturn(List.of(
                    new DailyAmount(LocalDate.of(2026, 8, 1), 1L, new BigDecimal("50000"))));
            when(port.capturedByTender(any(), any())).thenReturn(List.of(
                    new TenderRevenue(TenderType.CARD, true, 1L, new BigDecimal("45000")),
                    new TenderRevenue(TenderType.POINT, false, 1L, new BigDecimal("5000"))));

            RevenueReport report = report10Days();

            assertThat(report.unattributedAmount()).isEqualByComparingTo("0");
            assertThat(report.tenderBreakdownIsComplete()).isTrue();
        }

        /**
         * 수단 합이 총액을 넘는 것은 데이터가 깨졌다는 뜻이지 "음수 미상"이 아니다. 음수를 그대로
         * 내보내면 화면이 그 값을 다시 빼서 총액을 부풀린다.
         */
        @Test
        @DisplayName("수단 합이 총액을 넘어도 미상은 음수가 되지 않는다")
        void 미상은_음수가_되지_않는다() {
            when(port.capturesByDay(any(), any())).thenReturn(List.of(
                    new DailyAmount(LocalDate.of(2026, 8, 1), 1L, new BigDecimal("10000"))));
            when(port.capturedByTender(any(), any())).thenReturn(List.of(
                    new TenderRevenue(TenderType.CARD, true, 1L, new BigDecimal("30000"))));

            assertThat(report10Days().unattributedAmount()).isEqualByComparingTo("0");
        }

        /**
         * POINT·GIFT_CARD 는 내부 잔액 차감이라 이 기간에 새로 들어온 현금이 아니다 — 상품권은
         * 팔릴 때 이미 한 번 수납됐다. 카드와 한 줄에 합치면 그만큼 이중으로 센다.
         */
        @Test
        @DisplayName("내부 잔액 수단은 외부 PG 축으로 갈라 볼 수 있게 내려간다")
        void 내부잔액_축이_남는다() {
            when(port.capturedByTender(any(), any())).thenReturn(List.of(
                    new TenderRevenue(TenderType.CARD, true, 1L, new BigDecimal("45000")),
                    new TenderRevenue(TenderType.GIFT_CARD, false, 1L, new BigDecimal("5000"))));

            assertThat(report10Days().byTender())
                    .filteredOn(t -> !t.usesExternalPg())
                    .extracting(TenderRevenue::tenderType)
                    .containsExactly(TenderType.GIFT_CARD);
        }
    }

    @Test
    @DisplayName("아무 데이터도 없으면 모든 합계가 0 이고 순매출도 0 이다")
    void 빈_기간() {
        RevenueReport report = report10Days();

        assertThat(report.daily()).isEmpty();
        assertThat(report.byTender()).isEmpty();
        assertThat(report.capturedAmount()).isEqualByComparingTo("0");
        assertThat(report.netAmount()).isEqualByComparingTo("0");
        assertThat(report.tenderBreakdownIsComplete()).isTrue();
    }

    private RevenueReport report10Days() {
        return service.report(new RevenueQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10)));
    }
}
