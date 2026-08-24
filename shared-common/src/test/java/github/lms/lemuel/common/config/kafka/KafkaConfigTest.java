package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KafkaConfig 의 토픽 프로비저닝 배선 (ADR 0035).
 *
 * <p>배선이 끊기면 토픽이 브로커 자동생성으로 되돌아가고 파티션 수가 다시 코드 밖으로 나간다 —
 * 컴파일도 다른 테스트도 잡지 못하므로 여기서 못박는다.
 */
class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig("order-service");
    private final KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of("bootstrap.servers", "localhost:9092"));

    @Test
    @DisplayName("TopicAdmin 은 AdminClient 어댑터로 배선된다")
    void wiresTopicAdmin() {
        assertThat(config.topicAdmin(kafkaAdmin)).isInstanceOf(KafkaClientTopicAdmin.class);
    }

    @Test
    @DisplayName("프로비저너와 초기화 빈이 카탈로그·소유자와 함께 배선된다")
    void wiresProvisioningChain() {
        TopicProvisioner provisioner = config.topicProvisioner(config.topicAdmin(kafkaAdmin));

        TopicProvisioningInitializer initializer = config.topicProvisioningInitializer(
                config.topicCatalog(), provisioner, new SimpleMeterRegistry());

        assertThat(initializer).isNotNull();
    }

    @Test
    @DisplayName("owner 가 비면 프로비저닝을 건너뛴다 — 컨슈머 전용 서비스의 기본 상태")
    void consumerOnlyServiceSkipsProvisioning() {
        KafkaConfig consumerOnly = new KafkaConfig("");

        TopicProvisioningInitializer initializer = consumerOnly.topicProvisioningInitializer(
                consumerOnly.topicCatalog(),
                new TopicProvisioner(consumerOnly.topicAdmin(kafkaAdmin)),
                new SimpleMeterRegistry());

        assertThat(initializer.provisionQuietly())
                .as("브로커에 접속하지 않고 0 을 돌려줘야 한다")
                .isZero();
    }
}
