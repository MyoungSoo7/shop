package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLT 목적지 결정 규칙 검증.
 *
 * <p>이 규칙이 틀어져도 컨텍스트는 정상 기동하고 테스트는 초록으로 남는다 — 어긋난 사실은
 * 운영에서 "DLT 에 넣었는데 어디에도 없다"(존재하지 않는 파티션) 또는 {@code .DLT.DLT} 증식으로
 * 뒤늦게 드러난다. 그래서 배선 테스트와 별도로 목적지 계산 자체를 고정한다.
 */
class DltDestinationResolverTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Counter published = Counter.builder("test.kafka.dlt.published").register(registry);
    private final DltDestinationResolver resolver = new DltDestinationResolver(published);

    private static ConsumerRecord<String, String> record(String topic, int partition) {
        return new ConsumerRecord<>(topic, partition, 0L, "key", "value");
    }

    @Test
    @DisplayName("<원본>.DLT 로 보내되 파티션은 프로듀서에게 맡긴다(-1) — 원본 파티션 번호를 고정하면 DLT 에 그 번호가 없을 때 격리가 통째로 실패한다")
    void routesToDltLettingProducerChoosePartition() {
        // 실측 근거: lemuel.payment.captured 는 파티션 6개인데 lemuel.payment.captured.DLT 는 3개다.
        // 원본 파티션 번호를 그대로 쓰면 파티션 3~5 의 실패 레코드는 존재하지 않는 목적지로 향한다.
        // 파티션 수는 늘리기만 가능하고 DLT 가 자동으로 따라오지도 않으므로, "파티션 수가 같아야 한다"는
        // 전제 자체를 없앤다. 키가 보존되므로 키 단위 순서는 프로듀서 파티셔너가 그대로 지킨다.
        TopicPartition destination = resolver.apply(
                record("lemuel.payment.captured", 4), new IllegalStateException("boom"));

        assertThat(destination.topic()).isEqualTo("lemuel.payment.captured.DLT");
        assertThat(destination.partition()).isNegative();
    }

    @Test
    @DisplayName("이미 .DLT 인 토픽은 다시 접미하지 않는다 — .DLT.DLT 무한 증식 방지")
    void doesNotSuffixDltTwice() {
        TopicPartition destination = resolver.apply(
                record("lemuel.payment.captured.DLT", 0), new IllegalStateException("boom"));

        assertThat(destination.topic()).isEqualTo("lemuel.payment.captured.DLT");
    }

    @Test
    @DisplayName("격리 1건마다 카운터가 오른다 — alert-rules.yml 의 DLT 알람 임계가 이 값을 본다")
    void countsEveryQuarantinedRecord() {
        resolver.apply(record("lemuel.order.created", 0), new IllegalStateException("boom"));
        resolver.apply(record("lemuel.order.created", 1), new IllegalArgumentException("bad payload"));

        assertThat(published.count()).isEqualTo(2.0d);
    }
}
