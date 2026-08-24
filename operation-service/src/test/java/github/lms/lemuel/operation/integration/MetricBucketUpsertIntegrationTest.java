package github.lms.lemuel.operation.integration;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.signal.adapter.out.persistence.MetricBucketId;
import github.lms.lemuel.operation.signal.adapter.out.persistence.SpringDataMetricBucketRepository;
import github.lms.lemuel.operation.signal.application.port.out.UpsertMetricBucketPort;
import github.lms.lemuel.operation.signal.domain.MetricBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ops_metric_bucket UPSERT(ON CONFLICT DO UPDATE) 누적 시맨틱 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway.
 *
 * <p>Kafka/Prometheus 는 꺼둔 채(app.kafka.enabled=false, prometheus.enabled 미설정) 영속 어댑터만 직접 호출해
 * 카운터/게이지 누적이 원자적으로 합산되는지 확인한다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class MetricBucketUpsertIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("operation_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    UpsertMetricBucketPort upsertPort;
    @Autowired
    SpringDataMetricBucketRepository repository;

    private static final Instant BUCKET = Instant.parse("2026-07-07T06:00:00Z");

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    private MetricBucket load(String metricKey) {
        return repository.findById(new MetricBucketId(metricKey, BUCKET)).orElseThrow().toDomain();
    }

    @Test
    @DisplayName("카운터: 성공/실패 이벤트가 같은 버킷에 count_total/count_signal 로 누적된다")
    void counterUpsertAccumulates() {
        // 성공 3건(분모만) + 실패 2건(분모+분자)
        upsertPort.incrementEvent("payment", BUCKET, false);
        upsertPort.incrementEvent("payment", BUCKET, false);
        upsertPort.incrementEvent("payment", BUCKET, false);
        upsertPort.incrementEvent("payment", BUCKET, true);
        upsertPort.incrementEvent("payment", BUCKET, true);

        MetricBucket bucket = load("payment");
        assertThat(bucket.countTotal()).isEqualTo(5);
        assertThat(bucket.countSignal()).isEqualTo(2);
        assertThat(bucket.failureRate()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    @DisplayName("게이지: 표본이 value_sum 합산·value_max 최댓값·sample_count 로 누적된다")
    void gaugeUpsertAccumulates() {
        upsertPort.accumulateGauge("kafka.lag.max", BUCKET, 100.0);
        upsertPort.accumulateGauge("kafka.lag.max", BUCKET, 500.0);
        upsertPort.accumulateGauge("kafka.lag.max", BUCKET, 300.0);

        MetricBucket bucket = load("kafka.lag.max");
        assertThat(bucket.sampleCount()).isEqualTo(3);
        assertThat(bucket.valueSum()).isCloseTo(900.0, within(1e-9));
        assertThat(bucket.valueMax()).isEqualTo(500.0);
        assertThat(bucket.average()).isCloseTo(300.0, within(1e-9));
    }

    @Test
    @DisplayName("서로 다른 metric_key/bucket_start 는 독립 행으로 분리된다")
    void distinctKeysAreSeparateRows() {
        Instant nextBucket = BUCKET.plusSeconds(300);
        upsertPort.incrementEvent("order", BUCKET, false);
        upsertPort.incrementEvent("settlement", BUCKET, false);
        upsertPort.incrementEvent("order", nextBucket, false);

        assertThat(repository.count()).isEqualTo(3);
        assertThat(load("order").countTotal()).isEqualTo(1);
    }
}
