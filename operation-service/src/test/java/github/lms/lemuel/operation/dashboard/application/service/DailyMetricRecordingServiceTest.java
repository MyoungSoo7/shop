package github.lms.lemuel.operation.dashboard.application.service;

import github.lms.lemuel.operation.dashboard.application.port.out.UpsertDailyMetricPort;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DailyMetricRecordingServiceTest {

    @Mock
    UpsertDailyMetricPort upsertPort;

    private DailyMetricRecordingService service() {
        return new DailyMetricRecordingService(upsertPort, "Asia/Seoul");
    }

    /**
     * 이 서비스가 하는 일은 사실상 이 판정 하나다 — <b>이 사건은 며칠자인가</b>.
     * UTC 오후 3시 이후는 KST 로 이미 다음 날이다.
     */
    @Test
    @DisplayName("UTC 로 어제 저녁인 사건도 KST 로 오늘이면 오늘 칸에 들어간다")
    void assignsDayInConfiguredZone() {
        service().record(DashboardMetric.ORDER_CREATED,
                Instant.parse("2026-08-24T23:30:00Z"), new BigDecimal("45000"));

        verify(upsertPort).accumulate(LocalDate.of(2026, 8, 25),
                DashboardMetric.ORDER_CREATED, new BigDecimal("45000"));
    }

    @Test
    @DisplayName("KST 자정 직전 사건은 아직 그 전날이다")
    void justBeforeLocalMidnightIsStillYesterday() {
        service().record(DashboardMetric.PAYMENT_CAPTURED,
                Instant.parse("2026-08-24T14:59:59Z"), new BigDecimal("1000"));

        verify(upsertPort).accumulate(LocalDate.of(2026, 8, 24),
                DashboardMetric.PAYMENT_CAPTURED, new BigDecimal("1000"));
    }

    /** 금액 미상은 0 으로 바꾸지 않고 그대로 넘긴다 — 구분은 어댑터가 세어 둔다. */
    @Test
    @DisplayName("금액이 없는 사건은 null 그대로 전달한다")
    void unknownAmountIsPassedThrough() {
        service().record(DashboardMetric.USER_REGISTERED,
                Instant.parse("2026-08-25T01:00:00Z"), null);

        verify(upsertPort).accumulate(LocalDate.of(2026, 8, 25),
                DashboardMetric.USER_REGISTERED, null);
    }
}
