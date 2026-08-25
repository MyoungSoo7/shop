package github.lms.lemuel.operation.dashboard.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 대시보드 한 화면치.
 *
 * <p><b>{@code asOf} 를 반드시 함께 주는 이유</b>: 이벤트로 채우는 집계는 원본보다 항상 조금
 * 늦다. 늦는다는 사실을 숨기면 방금 결제한 건이 안 보이는 운영자가 새로고침을 반복하다가
 * "대시보드가 고장났다"고 판단한다. 몇 초 늦은 값은 정상이지만, 얼마나 늦었는지 모르는 값은
 * 쓸 수 없다. 아직 오늘 아무 이벤트도 없으면 {@code null} 이며, 화면은 그때 시각 대신
 * "아직 없음"을 그려야 한다 — 어제 시각을 오늘의 기준으로 보여 주는 것이 가장 나쁘다.
 *
 * @param date             KST 기준 오늘
 * @param zone             날짜 경계를 판정한 타임존 이름(화면이 근거를 밝힐 수 있게)
 * @param asOf             집계가 마지막으로 갱신된 시각. 오늘 이벤트가 없으면 null
 * @param metrics          {@link DashboardMetric} 전 항목. 값이 없는 지표도 0 으로 채워 보낸다 —
 *                         빠진 항목과 0건이 화면에서 같아 보이면 안 된다
 * @param openIncidents    미해결(OPEN·ACKNOWLEDGED) 인시던트 수. 운영 서비스 자기 DB 라 이벤트 불필요
 * @param failedDispatches 오늘 실패한 알림 발송 건수(FAILED·PARTIAL). 역시 자기 DB
 */
public record TodayOverview(
        LocalDate date,
        String zone,
        Instant asOf,
        List<DailyMetric> metrics,
        long openIncidents,
        long failedDispatches
) {
}
