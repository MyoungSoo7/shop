package github.lms.lemuel.operation.dashboard.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 하루치 지표 한 줄.
 *
 * @param date               KST 캘린더 날짜
 * @param metric             지표
 * @param eventCount         집계된 이벤트 수
 * @param amountSum          금액 합계. 금액 축이 없는 지표는 0 이며 화면이 그리지 않는다.
 * @param amountUnknownCount 건수에는 들어갔지만 금액을 읽지 못한 이벤트 수 —
 *                           0 보다 크면 {@link #amountSum} 은 <b>하한</b>이지 정확한 합이 아니다.
 * @param updatedAt          이 줄이 마지막으로 갱신된 시각(= 화면이 표시할 "기준 시각"의 재료)
 */
public record DailyMetric(
        LocalDate date,
        DashboardMetric metric,
        long eventCount,
        BigDecimal amountSum,
        long amountUnknownCount,
        Instant updatedAt
) {

    /** 아직 한 건도 들어오지 않은 지표의 빈 줄 — 화면이 "값 없음"과 "0건"을 같게 그리도록. */
    public static DailyMetric empty(LocalDate date, DashboardMetric metric) {
        return new DailyMetric(date, metric, 0L, BigDecimal.ZERO, 0L, null);
    }

    /** 금액 합계를 사실로 읽어도 되는가. 미상 건이 섞였으면 화면이 그렇게 말해야 한다. */
    public boolean amountComplete() {
        return amountUnknownCount == 0;
    }
}
