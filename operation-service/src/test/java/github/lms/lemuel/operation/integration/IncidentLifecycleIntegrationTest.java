package github.lms.lemuel.operation.integration;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.incident.adapter.out.persistence.SpringDataIncidentRepository;
import github.lms.lemuel.operation.incident.adapter.out.persistence.SpringDataIncidentTimelineRepository;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.AlertCommand;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.IngestResult;
import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인시던트 Phase 1 종단 통합 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway(opslab 스키마).
 *
 * <p>검증 축:
 * <ol>
 *   <li>webhook(v4 JSON, Bearer 인증) → 인시던트 OPEN → 운영자 ack → resolved 수신 → AUTO_RESOLVED</li>
 *   <li>repeat_interval 재전송 멱등 — 활성 인시던트 1건 유지, occurrenceCount 만 증가</li>
 *   <li>동시 webhook 경쟁 — uq_incident_active + 재시도 폴백으로 활성 인시던트 중복 0</li>
 *   <li>보안 — webhook Bearer 불일치 401, 콘솔 API 무인증 401 / ADMIN 200, 터미널 재전이 409</li>
 * </ol>
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.ops.webhook.token=test-webhook-token"
        }
)
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class IncidentLifecycleIntegrationTest {

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
    MockMvc mockMvc;
    @Autowired
    IngestAlertUseCase ingestAlertUseCase;
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
    }

    private static String webhookJson(String status, String fingerprint, String endsAt) {
        // startsAt 을 절대시각으로 박으면 summary(24h window: firstSeenAt >= now-24h) 집계에서
        // 하루 뒤 CI 부터 인시던트가 창 밖으로 밀려나 byCategory 가 비는 시한폭탄이 된다.
        // (#138 main CI 실패 원인) — 항상 "5분 전" 상대시각으로 생성.
        String startsAt = java.time.Instant.now().minusSeconds(300).toString();
        return """
                {
                  "version": "4",
                  "groupKey": "{}:{alertname=\\"OutboxPendingBacklog\\"}",
                  "status": "%s",
                  "receiver": "operation-webhook",
                  "alerts": [
                    {
                      "status": "%s",
                      "fingerprint": "%s",
                      "labels": {"alertname": "OutboxPendingBacklog", "severity": "warning", "component": "outbox"},
                      "annotations": {"summary": "Outbox PENDING 적체", "description": "1000건 초과"},
                      "startsAt": "%s",
                      "endsAt": "%s"
                    }
                  ]
                }
                """.formatted(status, status, fingerprint, startsAt, endsAt);
    }

    /**
     * ADMIN 인증 post-processor — {@code @WithMockUser} 의 스레드로컬 컨텍스트는
     * 보안 체인의 SecurityContextHolderFilter 가 요청 시작 시 덮어쓰므로,
     * 요청 단위로 컨텍스트 저장소에 심어 주는 이 방식이 확실하다.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("admin@lemuel").roles("ADMIN");
    }

    private void postWebhook(String json) throws Exception {
        mockMvc.perform(post("/api/ops/webhook/alertmanager")
                        .header("Authorization", "Bearer test-webhook-token")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed", is(0)));
    }

    @Test
    @DisplayName("시나리오1: firing → OPEN → 운영자 ack → resolved 수신 → AUTO_RESOLVED 전체 흐름")
    void fullLifecycle_firingToAutoResolve() throws Exception {
        postWebhook(webhookJson("firing", "fp-lifecycle", "0001-01-01T00:00:00Z"));

        Long id = incidentRepository.findAll().getFirst().getId();
        mockMvc.perform(get("/api/ops/incidents/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.status", is("OPEN")))
                .andExpect(jsonPath("$.incident.category", is("KAFKA_BACKLOG")))
                .andExpect(jsonPath("$.incident.title", is("OutboxPendingBacklog")));

        mockMvc.perform(post("/api/ops/incidents/{id}/ack", id)
                        .with(admin())
                        .contentType("application/json")
                        .content("{\"note\": \"폴러 재시작 중\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.status", is("ACKNOWLEDGED")))
                .andExpect(jsonPath("$.incident.acknowledgedBy", is("admin@lemuel")));

        postWebhook(webhookJson("resolved", "fp-lifecycle", "2026-07-06T06:00:00Z"));

        mockMvc.perform(get("/api/ops/incidents/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.status", is("RESOLVED")))
                .andExpect(jsonPath("$.incident.resolvedBy", is("alertmanager")))
                .andExpect(jsonPath("$.timeline[0].eventType", is("OPENED")))
                .andExpect(jsonPath("$.timeline[1].eventType", is("ACKNOWLEDGED")))
                .andExpect(jsonPath("$.timeline[2].eventType", is("AUTO_RESOLVED")));

        // 해제 후 재전이 시도 → 409
        mockMvc.perform(post("/api/ops/incidents/{id}/ack", id).with(admin()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("시나리오2: repeat_interval 재전송 멱등 — 활성 인시던트 1건, occurrenceCount 증가")
    void repeatedFiring_isIdempotent() throws Exception {
        postWebhook(webhookJson("firing", "fp-repeat", "0001-01-01T00:00:00Z"));
        postWebhook(webhookJson("firing", "fp-repeat", "0001-01-01T00:00:00Z"));
        postWebhook(webhookJson("firing", "fp-repeat", "0001-01-01T00:00:00Z"));

        var all = incidentRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().toDomain().getOccurrenceCount()).isEqualTo(3);
        assertThat(all.getFirst().toDomain().getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    @DisplayName("시나리오3: 동시 webhook 경쟁 — 활성 인시던트 중복 0 (uq_incident_active + 재시도 폴백)")
    void concurrentWebhooks_produceSingleActiveIncident() throws Exception {
        int threads = 4;
        AlertCommand alert = new AlertCommand("fp-race", true,
                Map.of("alertname", "RaceAlert", "severity", "warning", "component", "outbox"),
                Map.of("summary", "race"), Instant.parse("2026-07-06T05:00:00Z"), null);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<IngestResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        results.add(ingestAlertUseCase.ingest(List.of(alert)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long activeCount = incidentRepository.findAll().stream()
                .map(e -> e.toDomain())
                .filter(i -> i.getSource() == IncidentSource.ALERTMANAGER
                        && "fp-race".equals(i.getCorrelationKey())
                        && EnumSet.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED).contains(i.getStatus()))
                .count();
        assertThat(activeCount).isEqualTo(1);
        // 경쟁에서 진 스레드는 재시도 폴백으로 refire 병합 — 전 스레드 성공(failed=0)
        assertThat(results).allSatisfy(r -> assertThat(r.failed()).isZero());
    }

    @Test
    @DisplayName("시나리오4: 보안 — webhook Bearer 불일치 401, 콘솔 무인증 401")
    void security_webhookAndConsole() throws Exception {
        mockMvc.perform(post("/api/ops/webhook/alertmanager")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType("application/json")
                        .content(webhookJson("firing", "fp-sec", "0001-01-01T00:00:00Z")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/ops/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("시나리오5: 목록 필터·summary — ADMIN 조회")
    void listAndSummary() throws Exception {
        postWebhook(webhookJson("firing", "fp-list-1", "0001-01-01T00:00:00Z"));
        postWebhook(webhookJson("firing", "fp-list-2", "0001-01-01T00:00:00Z"));

        mockMvc.perform(get("/api/ops/incidents").with(admin())
                        .param("status", "OPEN").param("category", "KAFKA_BACKLOG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)));

        mockMvc.perform(get("/api/ops/incidents/summary").with(admin()).param("window", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openTotal", is(2)))
                .andExpect(jsonPath("$.byCategory.KAFKA_BACKLOG", is(2)));
    }
}
