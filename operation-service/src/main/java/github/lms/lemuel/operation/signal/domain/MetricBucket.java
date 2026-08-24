package github.lms.lemuel.operation.signal.domain;

import java.time.Instant;

/**
 * 5분 신호 버킷 스냅샷 (읽기 모델) — ops_metric_bucket 한 행.
 *
 * <p>두 신호 유형을 한 구조로 통합한다:
 * <ul>
 *   <li>카운터형(Kafka 도메인 이벤트): {@code countTotal}(시도=분모), {@code countSignal}(실패=분자).
 *       {@link #failureRate()} = countSignal / countTotal.</li>
 *   <li>게이지형(Prometheus 폴링): {@code valueSum}/{@code valueMax}/{@code sampleCount}.
 *       {@link #average()} = valueSum / sampleCount.</li>
 * </ul>
 * 적재는 UPSERT(누적)이며 도메인 계산은 읽기 시점에 한다.
 */
public record MetricBucket(
        String metricKey,
        Instant bucketStart,
        long countTotal,
        long countSignal,
        double valueSum,
        Double valueMax,
        long sampleCount
) {

    /** 카운터 실패율. 분모 0 이면 0.0 (표본 없음 — 판정에서 최소표본 게이트로 걸러짐). */
    public double failureRate() {
        return countTotal == 0 ? 0.0 : (double) countSignal / countTotal;
    }

    /** 게이지 평균. 표본 0 이면 0.0. */
    public double average() {
        return sampleCount == 0 ? 0.0 : valueSum / sampleCount;
    }
}
