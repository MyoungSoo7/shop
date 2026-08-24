package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Kafka 인프라 설정.
 *
 * <p>app.kafka.enabled=true 일 때만 활성화. 비활성 시 spring-kafka 오토컨피그가
 * 부분적으로 뜨지 않도록 @EnableKafka 를 이 조건부 빈에 모았다.
 *
 * <p><b>토픽 정의는 여기 있지 않다.</b> 파티션·보존기간은 {@code kafka/topic-catalog.json} 이 정본이고
 * (ADR 0035), 각 모듈은 자기가 <b>발행하는</b> 토픽만 만든다({@code app.kafka.topic.owner}).
 * 예전에는 이 클래스가 {@code NewTopic} 빈으로 payment 계열 4개만 선언했고 나머지 40여 개는 브로커
 * 자동생성에 맡겨져 파티션 수가 코드 밖에 있었다 — 키 기반 순서 보장을 쓰는 구조에서 파티션 수는
 * 되돌릴 수 없는 결정이므로 리뷰 대상이어야 한다.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    /**
     * 이 모듈이 발행자로서 소유한 토픽을 식별하는 Gradle 모듈명 (예: {@code order-service}).
     * 컨슈머 전용 서비스는 비워 둔다 — 토픽을 만드는 주체는 프로듀서 하나뿐이다.
     */
    private final String owner;

    public KafkaConfig(@Value("${app.kafka.topic.owner:}") String owner) {
        this.owner = owner;
    }

    @Bean
    public TopicCatalog topicCatalog() {
        return TopicCatalog.loadDefault();
    }

    @Bean
    public TopicAdmin topicAdmin(KafkaAdmin kafkaAdmin) {
        return new KafkaClientTopicAdmin(kafkaAdmin);
    }

    @Bean
    public TopicProvisioner topicProvisioner(TopicAdmin topicAdmin) {
        return new TopicProvisioner(topicAdmin);
    }

    @Bean
    public TopicProvisioningInitializer topicProvisioningInitializer(
            TopicCatalog topicCatalog, TopicProvisioner topicProvisioner, MeterRegistry meterRegistry) {
        return new TopicProvisioningInitializer(topicCatalog, topicProvisioner, owner, meterRegistry);
    }
}
