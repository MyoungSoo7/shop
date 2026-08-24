package github.lms.lemuel.tracefixture.adapter.in.kafka;

/**
 * {@code ..adapter.in.kafka..} 패키지 컨벤션을 모사하는 테스트 픽스처 —
 * LemuelPointcuts 의 Kafka 컨슈머 매칭 검증 전용.
 */
public class SampleKafkaConsumer {

    /** MDC 를 관측해 traceId 부여 여부를 밖으로 흘려주는 소비 메서드. */
    public String consume(java.util.concurrent.atomic.AtomicReference<String> observedTraceId) {
        observedTraceId.set(org.slf4j.MDC.get("traceId"));
        return "consumed";
    }
}
