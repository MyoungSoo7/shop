package github.lms.lemuel.operation.dashboard.application.port.in;

import github.lms.lemuel.operation.dashboard.domain.MetricTrend;

import java.time.LocalDate;
import java.util.List;

/**
 * 기간 추이 한 화면치를 조립한다.
 *
 * <p>{@link ViewTodayOverviewUseCase} 와 나누어 두는 이유는 <b>질문이 다르기 때문</b>이다.
 * "오늘 한눈에"는 하루를 세로로 훑어 인시던트·발송실패까지 함께 보는 운영 상태 화면이고,
 * 이쪽은 한 지표를 가로로 늘어놓아 <b>늘고 있는지 줄고 있는지</b>만 본다. 한 유스케이스에
 * 합치면 추이 조회가 매번 인시던트 수까지 세게 되는데, 그 값은 추이에서 쓰이지도 않는다.
 */
public interface ViewMetricTrendUseCase {

    /**
     * @param query 조회 조건
     * @return 구멍이 0 으로 채워진 날짜별 계열과 기간 합계
     */
    MetricTrend view(TrendQuery query);

    /**
     * 추이 조회 조건.
     *
     * <p>셋 다 {@code null} 을 허용한다 — 기본값(최근 30일·전 지표)은 서비스가 정한다.
     * 컨트롤러가 정하면 기본값이 화면 수만큼 갈라져, 같은 "최근"이 화면마다 달라진다.
     *
     * @param from    시작일(포함). {@code null} 이면 {@code to} 에서 역산
     * @param to      종료일(포함). {@code null} 이면 오늘(설정 타임존 기준)
     * @param metrics 좁혀 볼 지표. {@code null} 이거나 비면 전 항목
     */
    record TrendQuery(LocalDate from, LocalDate to, List<String> metrics) {
    }
}
