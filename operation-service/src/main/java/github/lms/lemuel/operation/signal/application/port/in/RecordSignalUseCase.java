package github.lms.lemuel.operation.signal.application.port.in;

import java.time.Instant;

/**
 * 신호 1건을 시계열 버킷에 누적하는 유스케이스.
 *
 * <p>Kafka 컨슈머(카운터)와 Prometheus 폴러(게이지)가 공용한다. 버킷 정렬·UPSERT 는 구현이 맡고,
 * 호출자는 "무슨 신호가 언제 얼마" 만 넘긴다.
 */
public interface RecordSignalUseCase {

    /**
     * 카운터 이벤트 1건 누적.
     *
     * @param metricKey 버킷 키 (예: "payment", "order", "settlement")
     * @param signal    관심 신호(실패) 여부 — true 면 countSignal 도 +1. 성공 이벤트는 false.
     * @param occurredAt 이벤트 발생 시각 (버킷 정렬 기준)
     */
    void recordEvent(String metricKey, boolean signal, Instant occurredAt);

    /**
     * 게이지 표본 1건 누적 (Prometheus 관측값).
     *
     * @param metricKey  버킷 키 (예: "kafka.lag.max", "redis.up")
     * @param value      관측값
     * @param observedAt 관측 시각 (버킷 정렬 기준)
     */
    void recordGauge(String metricKey, double value, Instant observedAt);
}
