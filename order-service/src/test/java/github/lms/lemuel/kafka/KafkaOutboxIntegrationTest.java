package github.lms.lemuel.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.OutboxPublisherScheduler;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka + Postgres 실제 컨테이너로 E2E 파이프라인 검증:
 *
 * <pre>
 *   outbox_events (PENDING) 저장
 *     → OutboxPublisherScheduler 폴링
 *       → KafkaOutboxPublisher.publish → Kafka topic 발행
 *         → raw Kafka consumer 로 메시지 수신 확인
 *         → outbox_events.status = PUBLISHED 로 전이 확인
 * </pre>
 *
 * <p>앱 내부 {@link github.lms.lemuel.settlement.adapter.in.kafka.PaymentEventKafkaConsumer}
 * 는 스키마 의존(JPA 엔티티 다수)으로 E2E 검증이 복잡하므로, 본 테스트는 발행 경로에 집중한다.
 * 컨슈머 로직의 핵심(멱등 체크, 정산 서비스 호출) 은 별도 단위 테스트로 검증한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@SpringBootTest(
        classes = github.lms.lemuel.LemuelApplication.class,
        properties = {
                "app.kafka.enabled=true",
                "app.outbox.polling-delay-ms=500",
                "spring.batch.job.enabled=false",
                // 앱 자체 Kafka 컨슈머(PaymentEventKafkaConsumer) 는 본 테스트에서 비활성화하고
                // raw KafkaConsumer 로 메시지 수신을 직접 검증한다.
                "spring.kafka.listener.auto-startup=false"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KafkaOutboxIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("inter")
            .withUsername("lemuel")
            .withPassword("lemuel");

    @Container
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired SaveOutboxEventPort saveOutboxEventPort;
    @Autowired LoadOutboxEventPort loadOutboxEventPort;
    @Autowired OutboxPublisherScheduler outboxPublisherScheduler;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("Outbox 저장 이벤트가 Kafka 토픽으로 실제 발행되고 PUBLISHED 상태로 전이된다")
    void outboxEventFlowsToKafka() throws Exception {
        // 1. PENDING outbox 이벤트 저장 (비즈니스 트랜잭션 대신 직접 저장)
        String payload = objectMapper.writeValueAsString(Map.of(
                "paymentId", 12345L,
                "orderId", 999L,
                "amount", "50000"
        ));
        OutboxEvent event = saveOutboxEventPort.save(
                OutboxEvent.pending("Payment", "12345", "PaymentCaptured", payload)
        );
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // 2. raw KafkaConsumer 로 topic 구독
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "test-verification-group", "true");
        consumerProps.put("auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                consumerProps,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer())) {

            consumer.subscribe(List.of("lemuel.payment.captured"));

            // 3. 스케줄러를 즉시 한 번 수동 트리거 (fixedDelay 대기 단축)
            outboxPublisherScheduler.publishPendingEvents();

            // 4. Kafka 에서 메시지가 도달할 때까지 최대 10초 대기
            ConsumerRecord<String, String> received = awaitFirstRecord(consumer);

            assertThat(received.key()).isEqualTo("12345");
            // Postgres JSONB 저장→조회 과정에서 키 정렬/공백 정규화가 일어날 수 있으므로
            // 관대한 검증 (핵심 필드 존재 여부만)
            assertThat(received.value()).contains("12345").contains("999").contains("50000");
            assertThat(received.headers().lastHeader("event_id")).isNotNull();
            assertThat(received.headers().lastHeader("event_type")).isNotNull();
            assertThat(received.headers().lastHeader("aggregate_type")).isNotNull();
        }

        // 5. outbox 상태가 PUBLISHED 로 전이됨 (스케줄러가 다시 돌아 상태 업데이트)
        long pendingDeadline = System.currentTimeMillis() + 8_000;
        while (loadOutboxEventPort.countPending() > 0 && System.currentTimeMillis() < pendingDeadline) {
            outboxPublisherScheduler.publishPendingEvents();
            Thread.sleep(200);
        }
        assertThat(loadOutboxEventPort.countPending()).isZero();
    }

    private ConsumerRecord<String, String> awaitFirstRecord(KafkaConsumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("Kafka 에서 15 초 내에 메시지를 수신하지 못했습니다.");
    }
}
