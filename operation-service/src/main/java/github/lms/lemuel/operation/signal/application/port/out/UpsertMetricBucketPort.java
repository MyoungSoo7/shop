package github.lms.lemuel.operation.signal.application.port.out;

import java.time.Instant;

/**
 * 신호 버킷 UPSERT 아웃바운드 포트 — (metric_key, bucket_start) 충돌 시 누적 갱신.
 *
 * <p>동시 다중 컨슈머/폴러가 같은 버킷에 몰려도 DB 원자 UPSERT(ON CONFLICT DO UPDATE)로
 * 경쟁 없이 누적된다. 앱 레벨 락 불필요.
 */
public interface UpsertMetricBucketPort {

    /**
     * 카운터 이벤트 누적: count_total += 1, count_signal += (signal ? 1 : 0).
     */
    void incrementEvent(String metricKey, Instant bucketStart, boolean signal);

    /**
     * 게이지 표본 누적: value_sum += value, value_max = max(value_max, value), sample_count += 1.
     */
    void accumulateGauge(String metricKey, Instant bucketStart, double value);
}
