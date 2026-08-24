package github.lms.lemuel.operation.anomaly.application.port.out;

import github.lms.lemuel.operation.signal.domain.MetricBucket;

import java.time.Instant;
import java.util.List;

/**
 * 이상 탐지 입력 시계열 조회 포트 — ops_metric_bucket 의 카운터형 버킷을 읽는다.
 *
 * <p>signal BC 가 소유한 {@code MetricBucket}(순수 읽기 모델)을 그대로 재사용한다 —
 * 같은 테이블의 읽기 좌표이므로 별도 모델을 두지 않는다.
 */
public interface LoadMetricSeriesPort {

    /**
     * 지정 metric_key 의 <b>마감된</b>(bucket_start &lt; before) 버킷을 최신 순으로 최대 limit 개 읽어
     * <b>시간 오름차순(과거→현재)</b>으로 반환한다. 판정 로직이 인덱스로 다루기 쉽도록 정렬을 고정한다.
     *
     * @param metricKey 대상 metric_key (예 "settlement")
     * @param before    이 시각 미만의 버킷만 (현재 진행 중인 미마감 버킷 제외)
     * @param limit     최대 조회 개수 (windowSize + resolveStreakK 정도)
     * @return 오름차순 버킷 목록 (없으면 빈 리스트)
     */
    List<MetricBucket> loadClosedBuckets(String metricKey, Instant before, int limit);
}
