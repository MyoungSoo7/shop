package github.lms.lemuel.operation.signal.application.port.out;

import github.lms.lemuel.operation.signal.domain.MetricBucket;

import java.time.Instant;
import java.util.List;

/**
 * 신호 버킷 조회 아웃바운드 포트 — 적재({@link UpsertMetricBucketPort})의 읽기 짝.
 */
public interface LoadMetricBucketPort {

    /**
     * 지정 metric_key 의 <b>마감된</b>(bucket_start &lt; before) 버킷을
     * <b>시간 오름차순(과거→현재)</b>으로 최대 limit 개 반환한다.
     *
     * <p>정렬을 포트 계약으로 고정한다 — 저장소는 인덱스 사정상 역순으로 읽는 편이 빠르지만,
     * 그건 저장소 사정이고 호출자가 알 일이 아니다.
     *
     * @return 오름차순 버킷 목록 (없으면 빈 리스트)
     */
    List<MetricBucket> findClosedBuckets(String metricKey, Instant before, int limit);
}
