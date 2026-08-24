package github.lms.lemuel.operation.integration;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.anomaly.application.port.in.DetectAnomaliesUseCase;
import github.lms.lemuel.operation.anomaly.application.port.in.DetectAnomaliesUseCase.DetectionSummary;
import github.lms.lemuel.operation.incident.adapter.out.persistence.IncidentJpaEntity;
import github.lms.lemuel.operation.incident.adapter.out.persistence.SpringDataIncidentRepository;
import github.lms.lemuel.operation.incident.adapter.out.persistence.SpringDataIncidentTimelineRepository;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import github.lms.lemuel.operation.incident.domain.TimelineEventType;
import github.lms.lemuel.operation.signal.domain.BucketWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 이상 탐지 종단 통합 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway(opslab).
 *
 * <p><b>합성 버킷 백테스트</b>: {@code ops_metric_bucket} 에 정상 baseline + 주입 스파이크를 직접 적재하고
 * {@link DetectAnomaliesUseCase} 를 실행해, (1) 스파이크만 탐지되고 (2) 정상 트래픽은 미탐이며
 * (3) 정상 복귀가 지속되면 자동 해제됨을 증명한다(seed acceptance_criteria 4·1·2·3).
 *
 * <p>스케줄러(app.ops.anomaly.enabled)는 끈 채 UseCase 를 직접 호출한다 — 판정 로직만 격리 검증.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.ops.webhook.token=test-webhook-token"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class AnomalyDetectionIntegrationTest {

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

    // application.yml 기본값: windowSize 12, z 3.0, criticalZ 5.0, minSample 30, floor 0.10, K 3
    private static final int WINDOW = 12;
    private static final int K = 3;
    private static final int BUCKET_SECONDS = 300;

    @Autowired
    DetectAnomaliesUseCase detectAnomaliesUseCase;
    @Autowired
    SpringDataIncidentRepository incidentRepository;
    @Autowired
    SpringDataIncidentTimelineRepository timelineRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        // incident_timeline 은 append-only 트리거(V20260715154000)가 DELETE 를 차단한다.
        // row 트리거는 TRUNCATE 에 발화하지 않으므로 테스트 격리 초기화는 TRUNCATE 로 수행.
        jdbcTemplate.execute("TRUNCATE TABLE opslab.incident_timeline");
        incidentRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM opslab.ops_metric_bucket");
    }

    /** 직전 마감 버킷 시작 시각(= 현재 진행 버킷 floor - 1버킷). */
    private Instant latestClosedBucketStart() {
        return BucketWindow.floor(Instant.now(), BUCKET_SECONDS).minusSeconds(BUCKET_SECONDS);
    }

    private void seedBucket(String metricKey, Instant bucketStart, long total, long signal) {
        jdbcTemplate.update("""
                INSERT INTO opslab.ops_metric_bucket
                    (metric_key, bucket_start, count_total, count_signal, value_sum, value_max, sample_count, updated_at)
                VALUES (?, ?, ?, ?, 0, NULL, 0, NOW())
                """, metricKey, OffsetDateTime.ofInstant(bucketStart, ZoneOffset.UTC), total, signal);
    }

    /** 정상 baseline(변동 있는 저실패율) count 개를 latest 이전에 적재. signal 1~3 순환으로 stddev>0 보장. */
    private void seedNormalBaseline(String metricKey, Instant latest, int count) {
        for (int i = 1; i <= count; i++) {
            seedBucket(metricKey, latest.minusSeconds((long) BUCKET_SECONDS * i), 100, 1 + (i % 3));
        }
    }

    private List<Incident> anomalyIncidents(String metricKey) {
        return incidentRepository.findAll().stream()
                .map(IncidentJpaEntity::toDomain)
                .filter(i -> i.getSource() == IncidentSource.ANOMALY && metricKey.equals(i.getCorrelationKey()))
                .toList();
    }

    @Test
    @DisplayName("주입 스파이크 → source=ANOMALY 인시던트 자동 생성 (정상 baseline 미탐)")
    void injectedSpike_opensAnomalyIncident() {
        Instant latest = latestClosedBucketStart();
        seedNormalBaseline("settlement", latest, WINDOW);   // 직전 12버킷 정상(≈1~3%)
        seedBucket("settlement", latest, 100, 40);          // 스파이크 40% (z 매우 큼)

        DetectionSummary summary = detectAnomaliesUseCase.detectOnce();

        assertThat(summary.opened()).isEqualTo(1);

        List<Incident> incidents = anomalyIncidents("settlement");
        assertThat(incidents).hasSize(1);
        Incident opened = incidents.getFirst();
        assertThat(opened.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(opened.getCategory()).isEqualTo(SignalCategory.SETTLEMENT_FAILURE);
        assertThat(opened.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);   // z >= 5

        assertThat(timelineRepository.findAll())
                .anyMatch(t -> t.toDomain().eventType() == TimelineEventType.OPENED
                        && Incident.ANOMALY_ACTOR.equals(t.toDomain().actor()));
    }

    @Test
    @DisplayName("정상 트래픽만 → 인시던트 미생성 (오탐 없음)")
    void normalTraffic_producesNoIncident() {
        Instant latest = latestClosedBucketStart();
        seedNormalBaseline("payment", latest, WINDOW);   // baseline
        seedBucket("payment", latest, 100, 2);           // 최신도 정상(2%)

        DetectionSummary summary = detectAnomaliesUseCase.detectOnce();

        assertThat(summary.opened()).isZero();
        assertThat(anomalyIncidents("payment")).isEmpty();
    }

    @Test
    @DisplayName("이상 후 정상 K회 연속 복귀 → AUTO_RESOLVED 자동 해제")
    void recovery_autoResolvesIncident() {
        Instant latest = latestClosedBucketStart();

        // Phase A: 스파이크로 인시던트 OPEN
        seedNormalBaseline("settlement", latest, WINDOW);
        seedBucket("settlement", latest, 100, 40);
        assertThat(detectAnomaliesUseCase.detectOnce().opened()).isEqualTo(1);
        assertThat(anomalyIncidents("settlement").getFirst().getStatus())
                .isEqualTo(IncidentStatus.OPEN);

        // Phase B: 버킷을 정상 시계열로 교체(최근 K개 포함 전부 정상) 후 재스캔
        jdbcTemplate.update("DELETE FROM opslab.ops_metric_bucket");
        Instant latest2 = latestClosedBucketStart();
        seedNormalBaseline("settlement", latest2, WINDOW + K);   // windowSize+K 만큼 정상
        seedBucket("settlement", latest2, 100, 2);              // 최신도 정상

        DetectionSummary summary = detectAnomaliesUseCase.detectOnce();

        assertThat(summary.resolved()).isEqualTo(1);
        Incident resolved = anomalyIncidents("settlement").getFirst();
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolvedBy()).isEqualTo(Incident.ANOMALY_ACTOR);
        assertThat(timelineRepository.findAll())
                .anyMatch(t -> t.toDomain().eventType() == TimelineEventType.AUTO_RESOLVED);
    }
}
