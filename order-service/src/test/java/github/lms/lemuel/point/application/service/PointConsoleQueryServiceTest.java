package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointAccountDetail;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointAccountRow;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointConsoleSummary;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointLedgerTotals;
import github.lms.lemuel.point.application.port.out.PointConsoleQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포인트 콘솔 조회 서비스 — 원시 집계를 3자 대조로 조립하고, 소멸 예정 창을 시각으로 바꾼다.
 *
 * <p>서비스에 있는 판단은 셋뿐이다: <b>3자 대조를 만든다</b>, <b>조회 상한을 클램프한다</b>,
 * <b>"며칠 이내"를 절대 시각으로 환산한다</b>. 나머지는 포트가 답한다.
 */
@ExtendWith(MockitoExtension.class)
class PointConsoleQueryServiceTest {

    @Mock
    PointConsoleQueryPort port;

    PointConsoleQueryService service;

    @BeforeEach
    void setUp() {
        service = new PointConsoleQueryService(port);
    }

    private static BigDecimal won(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("계정 상세")
    class AccountDetail {

        @Test
        @DisplayName("잔고·로트 합계·원장 누계를 모아 3자 대조를 만든다")
        void assemblesHealth() {
            when(port.findAccount(7L)).thenReturn(Optional.of(
                    new PointAccountRow(70L, "ACTIVE", won("1000"), won("0"), won("1000"))));
            when(port.activeLotRemaining(70L)).thenReturn(won("1000"));
            when(port.entryNet(70L)).thenReturn(won("1000"));
            when(port.recentLots(anyLong(), anyInt())).thenReturn(List.of());
            when(port.recentEntries(anyLong(), anyInt())).thenReturn(List.of());

            PointAccountDetail detail = service.account(7L).orElseThrow();

            assertThat(detail.accountId()).isEqualTo(70L);
            assertThat(detail.health().balanced()).isTrue();
        }

        @Test
        @DisplayName("어긋나면 균형이 깨진 것으로 보고한다 — 콘솔이 조사 대상을 지목할 수 있어야 한다")
        void reportsDrift() {
            when(port.findAccount(7L)).thenReturn(Optional.of(
                    new PointAccountRow(70L, "ACTIVE", won("1000"), won("0"), won("1000"))));
            when(port.activeLotRemaining(70L)).thenReturn(won("700"));
            when(port.entryNet(70L)).thenReturn(won("1000"));
            when(port.recentLots(anyLong(), anyInt())).thenReturn(List.of());
            when(port.recentEntries(anyLong(), anyInt())).thenReturn(List.of());

            PointAccountDetail detail = service.account(7L).orElseThrow();

            assertThat(detail.health().balanced()).isFalse();
            assertThat(detail.health().lotDrift()).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("포인트를 쓴 적 없는 사용자는 계정이 없다 — 비어 있는 결과로 답한다")
        void missingAccount() {
            when(port.findAccount(99L)).thenReturn(Optional.empty());

            assertThat(service.account(99L)).isEmpty();
        }

        @Test
        @DisplayName("계정이 없으면 뒤따르는 집계를 아예 묻지 않는다")
        void missingAccountSkipsAggregates() {
            when(port.findAccount(99L)).thenReturn(Optional.empty());

            service.account(99L);

            verify(port, org.mockito.Mockito.never()).activeLotRemaining(anyLong());
            verify(port, org.mockito.Mockito.never()).entryNet(anyLong());
        }
    }

    @Nested
    @DisplayName("전체 요약")
    class Summary {

        @Test
        @DisplayName("소멸 예정 창을 지금부터 N일 뒤 시각으로 환산해 묻는다")
        void convertsWindowToInstant() {
            when(port.overallTotals()).thenReturn(new PointLedgerTotals(
                    3L, won("1000"), won("1000"), won("1000"), 0L));
            when(port.expiringAmount(any())).thenReturn(won("250"));

            OffsetDateTime before = OffsetDateTime.now().plusDays(30);
            PointConsoleSummary summary = service.summary(30);
            OffsetDateTime after = OffsetDateTime.now().plusDays(30);

            ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(port).expiringAmount(captor.capture());
            assertThat(captor.getValue()).isBetween(before.minusSeconds(5), after.plusSeconds(5));
            assertThat(summary.expiringWithinDays()).isEqualTo(30);
            assertThat(summary.expiringAmount()).isEqualByComparingTo("250");
        }

        @Test
        @DisplayName("전체 집계를 그대로 실어 보낸다 — 드리프트 계정 수가 0 이 아니면 조사 신호다")
        void carriesTotals() {
            when(port.overallTotals()).thenReturn(new PointLedgerTotals(
                    5L, won("5000"), won("4800"), won("5000"), 2L));
            when(port.expiringAmount(any())).thenReturn(BigDecimal.ZERO);

            PointConsoleSummary summary = service.summary(30);

            assertThat(summary.accountCount()).isEqualTo(5L);
            assertThat(summary.driftedAccountCount()).isEqualTo(2L);
            assertThat(summary.totalActiveLotRemaining()).isEqualByComparingTo("4800");
        }

        @Test
        @DisplayName("일수가 0 이하면 1일로 올린다 — 과거를 소멸 예정이라 부를 수는 없다")
        void clampsWindowLow() {
            when(port.overallTotals()).thenReturn(new PointLedgerTotals(
                    0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L));
            when(port.expiringAmount(any())).thenReturn(BigDecimal.ZERO);

            assertThat(service.summary(0).expiringWithinDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("일수 상한은 365일이다 — 무기한 로트까지 끌어오는 조회가 되면 안 된다")
        void clampsWindowHigh() {
            when(port.overallTotals()).thenReturn(new PointLedgerTotals(
                    0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L));
            when(port.expiringAmount(any())).thenReturn(BigDecimal.ZERO);

            assertThat(service.summary(9999).expiringWithinDays()).isEqualTo(365);
        }
    }

    @Nested
    @DisplayName("소멸 예정 목록")
    class Expiring {

        @Test
        @DisplayName("조회 상한을 클램프한다 — 전 계정 로트를 한 번에 끌어오지 않는다")
        void clampsLimit() {
            when(port.expiringLots(any(), anyInt())).thenReturn(List.of());

            service.expiringLots(30, 100_000);

            verify(port).expiringLots(any(), org.mockito.ArgumentMatchers.eq(200));
        }

        @Test
        @DisplayName("만료 기준 시각은 지금부터 N일 뒤다")
        void windowFromNow() {
            when(port.expiringLots(any(), anyInt())).thenReturn(List.of());

            service.expiringLots(7, 20);

            ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(port).expiringLots(captor.capture(), anyInt());
            assertThat(Duration.between(OffsetDateTime.now(), captor.getValue()).toDays())
                    .isBetween(6L, 7L);
        }
    }
}
