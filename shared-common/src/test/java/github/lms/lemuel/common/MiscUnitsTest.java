package github.lms.lemuel.common;

import github.lms.lemuel.common.config.kafka.KafkaConfig;
import github.lms.lemuel.common.config.kafka.TopicCatalog;
import github.lms.lemuel.common.observability.aop.ObservabilityAopProperties;
import github.lms.lemuel.common.opssignal.NoOpOpsSignalPublisher;
import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 값/설정 객체 단위 검증 — KafkaConfig 토픽 정의, AOP 프로퍼티 접근자, NoOp opssignal.
 */
class MiscUnitsTest {

    @Test
    @DisplayName("KafkaConfig: 토픽 정의를 들고 있지 않고 카탈로그를 빈으로 노출한다 (ADR 0035)")
    void kafkaTopicsComeFromCatalog() {
        // 예전에는 이 설정이 payment 계열 4개만 NewTopic 으로 선언했고 나머지 40여 개는 브로커
        // 자동생성에 맡겨져 파티션 수가 코드 밖에 있었다. 이제 정본은 topic-catalog.json 이다.
        KafkaConfig config = new KafkaConfig("order-service");

        TopicCatalog catalog = config.topicCatalog();

        assertThat(catalog.find("lemuel.payment.captured"))
                .get()
                .extracting(TopicCatalog.Topic::owner)
                .isEqualTo("order-service");
        assertThat(catalog.find("lemuel.payment.captured").orElseThrow().deadLetterSpec().name())
                .isEqualTo("lemuel.payment.captured.DLT");
    }

    @Test
    @DisplayName("ObservabilityAopProperties: 기본값 + setter/getter")
    void observabilityProps() {
        ObservabilityAopProperties p = new ObservabilityAopProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getSlowThresholdMs()).isEqualTo(500);
        assertThat(p.isLogArgs()).isFalse();
        assertThat(p.getMaxArgLength()).isEqualTo(200);

        p.setEnabled(false);
        p.setSlowThresholdMs(1000);
        p.setLogArgs(true);
        p.setMaxArgLength(50);

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getSlowThresholdMs()).isEqualTo(1000);
        assertThat(p.isLogArgs()).isTrue();
        assertThat(p.getMaxArgLength()).isEqualTo(50);
    }

    @Test
    @DisplayName("NoOpOpsSignalPublisher: 두 emit 오버로드 모두 안전한 no-op")
    void noOpOpsSignal() {
        NoOpOpsSignalPublisher publisher = new NoOpOpsSignalPublisher();
        OpsSignal signal = new OpsSignal(OpsSignalCategory.SETTLEMENT_FAILED, "settlement-service",
                "Settlement", "1", OpsSignal.SEVERITY_ERROR, java.time.Instant.now(), Map.of("k", "v"));
        publisher.emit(signal);
        publisher.emit(OpsSignalCategory.PAYMENT_FAILED, "Payment", "2", Map.of("a", 1));
    }
}
