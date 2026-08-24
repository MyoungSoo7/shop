package github.lms.lemuel.common.config.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 전 서비스 공용 Kafka 컨슈머 에러 핸들링 배선 (재시도 → DLT).
 *
 * <p><b>왜 공용인가</b>: 이 배선은 원래 서비스마다 ~180줄씩 복붙되어 있었고(account·company·
 * investment·loan·settlement 5벌), 그 결과 나중에 추가된 서비스들(card·insurance·operation)은
 * 배선 자체가 누락되어 Spring Kafka 기본 핸들러 {@code FixedBackOff(0, 9)} 로 떨어졌다.
 * 기본 핸들러는 재시도 소진 후 메시지를 <b>조용히 skip</b> 한다 = 사실상 유실이며, 금액 이벤트에서는
 * 원장 영구 결손으로 직결된다. 사본이 남아 있는 한 다음 서비스에서 또 빠지므로 배선을 한 벌로 모았다.
 * 누락은 {@code scripts/harness/guard.mjs} 의 KAFKA-DLQ 규칙이 기계로 막는다.
 *
 * <p><b>동작</b>:
 * <ol>
 *   <li>일시적 예외(DB lock timeout, IO error) → {@code FixedBackOff(2s, 3회)} 재시도</li>
 *   <li>독성 메시지(파싱 실패·인풋 검증 실패·상태 위반) → 재시도 없이 즉시 DLT
 *       (서비스별 도메인 예외는 {@link NonRetryableConsumerExceptions} 로 기여)</li>
 *   <li>재시도 한계 도달 → {@code <topic>.DLT} 로 복사 후 ack — 같은 파티션 후속 메시지는 정상 처리</li>
 * </ol>
 *
 * <p>{@link DeadLetterPublishingRecoverer} 가 원본 헤더({@code event_id}·{@code traceparent})를
 * 패스스루하고 {@code kafka_dlt-*} 진단 헤더를 부여해 사후 추적과 멱등 replay 를 보장한다.
 * DLT 프로듀서는 {@code acks=all}+idempotence 로 DLT 자체의 손실을 막는다.
 *
 * <p><b>소비 전용 서비스 주의</b>(account 등): DLT publish 전용 프로듀서는 "이벤트 발행 금지"
 * 가드레일의 예외가 아니다 — 비즈니스 이벤트 발행이 아니라 실패 record 의 사본 격리이며,
 * Outbox 머시너리를 쓰지 않는다.
 *
 * <p><b>스캔</b>: 루트 {@code github.lms.lemuel} 를 스캔하는 서비스는 자동으로 잡힌다.
 * 제한 스캔 서비스(company 등)는 {@code @Import(KafkaConsumerErrorHandlingConfig.class)} 가 필요하다.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConsumerErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerErrorHandlingConfig.class);

    /** 재시도 간격(ms). */
    private static final long RETRY_INTERVAL_MS = 2_000L;
    /** 최대 재시도 횟수. 합계 6초(2s × 3) 동안 재시도. */
    private static final long MAX_RETRIES = 3L;

    /** 메트릭 접두 기본값 — {@code spring.application.name} 이 없을 때. */
    private static final String DEFAULT_METRIC_PREFIX = "app";
    /** 서비스 이름 관례 {@code lemuel-<service>} 의 접두. */
    private static final String APP_NAME_PREFIX = "lemuel-";

    private final MeterRegistry meterRegistry;
    private final String bootstrapServers;
    /**
     * 리스너 컨테이너 동시성 — 컨슈머 스레드 수. 유효 상한은 토픽 파티션 수(기본 3)이며 초과분은 idle 이다.
     * 멱등 3단 방어(outbox event_id UNIQUE → processed_events PK → 도메인 자연키 UNIQUE)로
     * 파티션 간 병렬 소비가 안전하다.
     */
    private final int concurrency;
    /** 메트릭 접두 — 기존 알람·대시보드가 참조하는 이름을 그대로 재현한다. */
    private final String metricPrefix;

    public KafkaConsumerErrorHandlingConfig(
            MeterRegistry meterRegistry,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.consumer.concurrency:3}") int concurrency,
            @Value("${app.kafka.metrics-prefix:}") String metricPrefixOverride,
            @Value("${spring.application.name:}") String applicationName) {
        this.meterRegistry = meterRegistry;
        this.bootstrapServers = bootstrapServers;
        this.concurrency = concurrency;
        this.metricPrefix = (metricPrefixOverride == null || metricPrefixOverride.isBlank())
                ? resolveMetricPrefix(applicationName)
                : metricPrefixOverride;
    }

    /**
     * {@code spring.application.name} 에서 메트릭 접두를 유도한다.
     *
     * <p>{@code lemuel-settlement} → {@code settlement}. 이 변환이 기존
     * {@code monitoring/alert-rules.yml}·Grafana 대시보드가 참조하는
     * {@code settlement_kafka_dlt_published_total} 을 그대로 유지시킨다 — 바꾸면 알람이 조용히 죽는다.
     */
    public static String resolveMetricPrefix(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return DEFAULT_METRIC_PREFIX;
        }
        return applicationName.startsWith(APP_NAME_PREFIX)
                ? applicationName.substring(APP_NAME_PREFIX.length())
                : applicationName;
    }

    /** DLT publish 전용 ProducerFactory — String 값 통과, acks=all+idempotence 로 손실 방지. */
    @Bean
    public ProducerFactory<String, String> dltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> dltKafkaTemplate(ProducerFactory<String, String> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /**
     * 컨슈머 ConsumerFactory — String key/value, 수동 커밋·{@code read_committed}.
     *
     * <p>autoconfigure 의 동명 빈은 {@code @ConditionalOnMissingBean} 이라 이 빈이 우선한다.
     * {@code group-id} 는 각 서비스 {@code application.yml} 이 명시하며, 기본값은 안전한 폴백일 뿐이다.
     */
    @Bean
    public ConsumerFactory<String, String> kafkaConsumerFactory(
            @Value("${spring.kafka.consumer.group-id:${spring.application.name:lemuel-consumer}}") String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * DLT recoverer — 재시도 소진 시 원본 record 를 {@code <topic>.DLT} 로 복사한다.
     *
     * <p>{@code @Qualifier} 는 장식이 아니다 — 서비스가 자체 {@code KafkaTemplate<String, String>} 을
     * 추가로 정의하면 타입 주입이 모호해져 기동이 깨진다. 이름 기반 폴백에 기대지 않고 명시한다.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterRecoverer(
            @Qualifier("dltKafkaTemplate") KafkaTemplate<String, String> dltKafkaTemplate) {
        Counter dltPublished = Counter.builder(metricPrefix + ".kafka.dlt.published")
                .description("Kafka 메시지가 재시도 끝에 DLT 로 publish 된 건수")
                .register(meterRegistry);
        return new DeadLetterPublishingRecoverer(dltKafkaTemplate, new DltDestinationResolver(dltPublished));
    }

    /**
     * DefaultErrorHandler — 재시도 + DLT 라우팅.
     *
     * <p>기본 즉시-DLT 예외: {@code JsonProcessingException}(페이로드 파싱 불가),
     * {@code IllegalArgumentException}(도메인 인풋 검증 실패), {@code IllegalStateException}(상태 위반).
     * 서비스별 도메인 타입 예외는 {@link NonRetryableConsumerExceptions} 빈으로 덧붙인다.
     */
    @Bean
    public DefaultErrorHandler kafkaConsumerErrorHandler(
            DeadLetterPublishingRecoverer deadLetterRecoverer,
            ObjectProvider<NonRetryableConsumerExceptions> contributors) {

        DefaultErrorHandler handler = new DefaultErrorHandler(
                deadLetterRecoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));

        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                IllegalStateException.class);
        contributors.orderedStream()
                .flatMap(contributor -> contributor.exceptions().stream())
                .forEach(handler::addNotRetryableExceptions);

        Counter retryCounter = Counter.builder(metricPrefix + ".kafka.retry")
                .description("Kafka 컨슈머 재시도 시도 횟수")
                .register(meterRegistry);
        handler.setRetryListeners((record, ex, deliveryAttempt) -> {
            retryCounter.increment();
            log.warn("[Kafka retry] topic={}, partition={}, offset={}, attempt={}, exception={}",
                    record.topic(), record.partition(), record.offset(),
                    deliveryAttempt, ex.getClass().getSimpleName());
        });
        return handler;
    }

    /**
     * 컨슈머 ListenerContainerFactory — autoconfigure 의 동명 빈을 override 한다.
     * {@code @KafkaListener(containerFactory = "kafkaListenerContainerFactory")} 가 이 빈을 참조한다.
     */
    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> kafkaConsumerFactory,
            DefaultErrorHandler kafkaConsumerErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaConsumerFactory);
        factory.setCommonErrorHandler(kafkaConsumerErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(concurrency);
        return factory;
    }
}
