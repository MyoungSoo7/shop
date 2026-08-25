package github.lms.lemuel.common.config.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공용 Kafka 컨슈머 에러 핸들링 배선 검증.
 *
 * <p>배경: Spring Kafka 기본 에러 핸들러({@code FixedBackOff(0, 9)})는 재시도 소진 후 메시지를
 * 조용히 skip 한다 = 사실상 유실. 이 배선이 활성화되지 않으면 금액 이벤트가 흔적 없이 사라진다.
 * 따라서 "빈이 뜨는가"가 아니라 "재시도·DLT·분류가 실제로 설정되는가"를 검증한다. (브로커 불요)
 */
class KafkaConsumerErrorHandlingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // withUserConfiguration = register() → @ConditionalOnProperty 가 실제로 평가된다
            // (withBean 은 조건 평가를 건너뛰어 "비활성" 케이스를 검증할 수 없다).
            .withUserConfiguration(TestSupportConfig.class, KafkaConsumerErrorHandlingConfig.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    // 이 저장소에 실재하는 이름을 쓴다(lemuel-settlement 는 여기 없는 서비스였다).
                    // lemuel-operation 은 접두를 떼는 경로도 함께 태운다 → 접두 operation.
                    "spring.application.name=lemuel-operation");

    @Configuration(proxyBeanMethods = false)
    static class TestSupportConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** account 처럼 도메인 타입 예외를 기여하는 서비스를 흉내낸다. */
    @Configuration(proxyBeanMethods = false)
    static class ContributingConfig {
        @Bean
        NonRetryableConsumerExceptions domainExceptions() {
            return () -> List.of(SampleDomainException.class);
        }
    }

    static class SampleDomainException extends RuntimeException {
        SampleDomainException(String message) {
            super(message);
        }
    }

    @Nested
    @DisplayName("활성화 조건")
    class Activation {

        @Test
        @DisplayName("app.kafka.enabled 미설정이면 배선하지 않는다 — Kafka 안 쓰는 프로파일 오염 방지")
        void inactiveByDefault() {
            runner.run(context -> assertThat(context)
                    .doesNotHaveBean(ConcurrentKafkaListenerContainerFactory.class)
                    .doesNotHaveBean(DefaultErrorHandler.class));
        }

        @Test
        @DisplayName("app.kafka.enabled=true 면 리스너 팩토리와 에러 핸들러가 배선된다")
        void activeWhenEnabled() {
            runner.withPropertyValues("app.kafka.enabled=true").run(context -> assertThat(context)
                    .hasSingleBean(ConcurrentKafkaListenerContainerFactory.class)
                    .hasSingleBean(DefaultErrorHandler.class)
                    .hasBean("kafkaListenerContainerFactory"));
        }
    }

    @Nested
    @DisplayName("리스너 컨테이너")
    class ListenerContainer {

        @Test
        @DisplayName("에러 핸들러가 컨테이너에 실제로 부착된다 — 이게 빠지면 기본 핸들러로 조용히 유실된다")
        void attachesErrorHandlerToContainerFactory() {
            runner.withPropertyValues("app.kafka.enabled=true").run(context -> {
                ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                        context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                assertThat(factory.getContainerProperties().getKafkaConsumerProperties()).isNotNull();
                // 리스너 컨테이너를 만들어 보면 공용 에러 핸들러가 주입되었는지 확인할 수 있다.
                assertThat(factory.createContainer("any-topic").getCommonErrorHandler())
                        .isSameAs(context.getBean(DefaultErrorHandler.class));
            });
        }

        @Test
        @DisplayName("ack 모드는 MANUAL_IMMEDIATE — 모든 컨슈머가 Acknowledgment 를 받아 직접 ack 하는 전제다")
        void usesManualImmediateAckMode() {
            // 이 값이 바뀌면 리스너 시그니처와 어긋난다: Acknowledgment 없는 리스너에 수동 모드를 주면
            // 오프셋이 영원히 커밋되지 않아 무한 재배달이 되고, 반대로 자동 모드를 주면 ack 호출이 무의미해진다.
            // (notification-service 는 리스너에 Acknowledgment 가 없어 RECORD 를 쓴다 — 별도 배선.)
            runner.withPropertyValues("app.kafka.enabled=true").run(context -> assertThat(
                    context.getBean(ConcurrentKafkaListenerContainerFactory.class)
                            .getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE));
        }

        @Test
        @DisplayName("동시성은 app.kafka.consumer.concurrency 를 따른다")
        void honoursConfiguredConcurrency() {
            runner.withPropertyValues("app.kafka.enabled=true", "app.kafka.consumer.concurrency=5")
                    .run(context -> assertThat(context.getBean(ConcurrentKafkaListenerContainerFactory.class))
                            .extracting("concurrency")
                            .isEqualTo(5));
        }
    }

    @Nested
    @DisplayName("예외 분류")
    class Classification {

        @Test
        @DisplayName("독성 메시지 예외 3종은 재시도 없이 즉시 DLT 로 분류된다")
        void defaultNonRetryableExceptions() {
            runner.withPropertyValues("app.kafka.enabled=true").run(context -> {
                DefaultErrorHandler handler = context.getBean(DefaultErrorHandler.class);
                assertThat(handler.removeClassification(JsonProcessingException.class)).isFalse();
                assertThat(handler.removeClassification(IllegalArgumentException.class)).isFalse();
                assertThat(handler.removeClassification(IllegalStateException.class)).isFalse();
            });
        }

        @Test
        @DisplayName("서비스가 기여한 도메인 예외도 재시도 대상에서 제외된다 — OO 게이트상 타입 예외 사용 서비스 대응")
        void registersServiceContributedExceptions() {
            runner.withUserConfiguration(ContributingConfig.class)
                    .withPropertyValues("app.kafka.enabled=true")
                    .run(context -> assertThat(context.getBean(DefaultErrorHandler.class)
                            .removeClassification(SampleDomainException.class)).isFalse());
        }

        @Test
        @DisplayName("기여자가 없어도 기동한다 — 대부분의 서비스는 기본 3종으로 충분하다")
        void worksWithoutContributors() {
            runner.withPropertyValues("app.kafka.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("메트릭 이름")
    class Metrics {

        @Test
        @DisplayName("spring.application.name 의 lemuel- 접두를 떼어 기존 알람 규칙과 같은 이름을 쓴다")
        void derivesPrefixFromApplicationName() {
            // 이 저장소가 실제로 의존하는 두 좌표 — 여기가 바뀌면 alert-rules.yml 의 룰이
            // 존재하지 않는 시계열을 보게 되고, 발화하지 않는 채로 조용히 죽는다.
            //   order-service     spring.application.name=lemuel           → lemuel_kafka_*
            //   operation-service spring.application.name=lemuel-operation → operation_kafka_*
            assertThat(KafkaConsumerErrorHandlingConfig.resolveMetricPrefix("lemuel"))
                    .isEqualTo("lemuel");
            assertThat(KafkaConsumerErrorHandlingConfig.resolveMetricPrefix("lemuel-operation"))
                    .isEqualTo("operation");
        }

        @Test
        @DisplayName("lemuel- 접두가 없거나 비어 있으면 그대로/기본값을 쓴다")
        void fallsBackForOtherNames() {
            assertThat(KafkaConsumerErrorHandlingConfig.resolveMetricPrefix("card")).isEqualTo("card");
            assertThat(KafkaConsumerErrorHandlingConfig.resolveMetricPrefix("")).isEqualTo("app");
            assertThat(KafkaConsumerErrorHandlingConfig.resolveMetricPrefix(null)).isEqualTo("app");
        }

        @Test
        @DisplayName("DLT·재시도 카운터가 서비스 접두로 등록된다")
        void registersPrefixedCounters() {
            runner.withPropertyValues("app.kafka.enabled=true").run(context -> {
                MeterRegistry registry = context.getBean(MeterRegistry.class);
                context.getBean(DefaultErrorHandler.class); // 지연 생성 방지
                assertThat(registry.find("operation.kafka.dlt.published").counter()).isNotNull();
                assertThat(registry.find("operation.kafka.retry").counter()).isNotNull();
            });
        }

        @Test
        @DisplayName("app.kafka.metrics-prefix 로 명시 override 할 수 있다")
        void allowsExplicitPrefixOverride() {
            runner.withPropertyValues("app.kafka.enabled=true", "app.kafka.metrics-prefix=custom")
                    .run(context -> assertThat(context.getBean(MeterRegistry.class)
                            .find("custom.kafka.dlt.published").counter()).isNotNull());
        }
    }
}
