package github.lms.lemuel.operation.anomaly.application.port.out;

import github.lms.lemuel.operation.anomaly.domain.MetricPoint;

import java.time.Instant;
import java.util.List;

/**
 * 이상 탐지 입력 시계열 조회 포트.
 *
 * <p>반환 타입은 anomaly 자신의 {@link MetricPoint} 다. 이전에는 signal 의 도메인 모델을
 * 그대로 노출했는데, 그러면 <b>포트가 다른 기능의 모델을 계약에 박아 넣는</b> 셈이라
 * 시계열을 어디서 가져오든 signal 을 벗어날 수 없게 된다.
 */
public interface LoadMetricSeriesPort {

    /**
     * 지정 metric_key 의 <b>마감된</b> 구간을 <b>시간 오름차순(과거→현재)</b>으로 최대 limit 개 읽는다.
     * 판정 로직이 인덱스로 다루므로 정렬을 계약으로 고정한다.
     *
     * <p>진행 중인(부분 집계) 구간을 무엇으로 보고 잘라낼지는 <b>공급자가 정한다</b> —
     * 탐지는 구간 폭을 알 필요가 없다.
     *
     * @param metricKey 대상 metric_key (예 "settlement")
     * @param asOf      기준 시각 — 이 시각이 속한 진행 중 구간은 제외된다
     * @param limit     최대 조회 개수 (windowSize + resolveStreakK 정도)
     * @return 오름차순 목록 (없으면 빈 리스트)
     */
    List<MetricPoint> loadClosedPoints(String metricKey, Instant asOf, int limit);
}
