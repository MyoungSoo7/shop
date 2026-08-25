package github.lms.lemuel.operation.dashboard.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 기간 추이 한 화면치.
 *
 * <p><b>왜 이게 필요한가</b>: {@code ops_daily_metric} 은 날짜별로 계속 쌓이는데, 읽는 곳이
 * "오늘 한눈에" 하나뿐이었다. 하루씩만 꺼낼 수 있으니 30일 추이를 그리려면 화면이 같은
 * 엔드포인트를 30번 부르는 수밖에 없고, 그래서 아무도 안 그렸다. 매일 적재되는 값이 아무도
 * 보지 않는 채로 쌓이고 있었던 셈이다 — 새 데이터를 만드는 것이 아니라 <b>이미 있는 데이터를
 * 꺼내는 것</b>이 이 기능의 전부다.
 *
 * <p>집계 테이블만 읽으므로 {@code TodayOverviewService} 와 같은 성질을 갖는다 — 다른 서비스의
 * DB 도 API 도 건드리지 않는다.
 *
 * @param from    시작일(포함). KST 캘린더 날짜
 * @param to      종료일(포함)
 * @param zone    날짜 경계를 판정한 타임존 이름 — 화면이 근거를 밝힐 수 있게 함께 보낸다
 * @param metrics 이 응답에 포함된 지표. 요청이 좁히지 않으면 전 항목
 * @param series  날짜 오름차순, 같은 날짜 안에서는 지표 선언 순서. <b>구멍은 0 으로 채워져 있다</b>
 * @param totals  기간 전체 지표별 합계
 * @param asOf    이 기간의 줄 중 마지막으로 갱신된 시각. 기간 안에 한 건도 없으면 {@code null}
 */
public record MetricTrend(
        LocalDate from,
        LocalDate to,
        String zone,
        List<DashboardMetric> metrics,
        List<DailyMetric> series,
        List<MetricTotal> totals,
        Instant asOf
) {

    /**
     * 기간 합계 한 줄.
     *
     * <p>화면이 {@link #series} 를 직접 더해도 같은 값이 나오지만, 그 덧셈을 화면에 맡기면
     * {@code amountUnknownCount} 를 잊는 순간 <b>하한값이 정확한 합계로 둔갑</b>한다. 합계와
     * "이 합계를 믿어도 되는가"는 항상 같이 다녀야 하므로 서버가 함께 계산해 보낸다.
     *
     * @param amountUnknownCount 기간 안에서 금액을 읽지 못한 이벤트 수. 0 보다 크면
     *                           {@link #amountSum} 은 <b>하한</b>이지 정확한 합이 아니다
     */
    public record MetricTotal(
            DashboardMetric metric,
            long eventCount,
            BigDecimal amountSum,
            long amountUnknownCount
    ) {

        /** 금액 합계를 사실로 읽어도 되는가. 거짓이면 화면이 "일부 미상"을 붙여야 한다. */
        public boolean amountComplete() {
            return amountUnknownCount == 0;
        }
    }
}
