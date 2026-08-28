package github.lms.lemuel.point.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보상 왕복의 <b>원장 쪽 반</b> — 요청을 받아 적립하고, 그 사실을 되돌려 보내는 데까지.
 *
 * <pre>
 *   lemuel.marketing.reward_requested (정본 샘플)
 *     → MarketingRewardConsumer
 *       → 포인트 원장 (계좌 개설 · 로트 발급 · 원장 엔트리)
 *         → outbox: lemuel.point.granted   ← marketing 이 이걸 받아 보상을 확정한다
 * </pre>
 *
 * <p>여기서 재려는 것은 마지막 화살표다. 적립이 됐다는 것만으로는 왕복이 닫히지 않는다 —
 * 돌아가는 이벤트에 <b>marketing 이 자기 것으로 알아볼 수 있는 참조</b>가 실려야 한다.
 * 그 참조는 {@code referenceType}(보상 종류) + {@code referenceId}(보상 id) 짝이고,
 * 그게 흔들리면 포인트는 들어갔는데 보상은 영원히 미확정으로 남는다. 어느 쪽도 에러를 내지 않는다.
 *
 * <p>목으로는 이 판정을 할 수 없다. {@code referenceType} 은 명령에서 로트로, 로트에서 다시
 * 이벤트 페이로드로 세 번 옮겨 실리는데, 그 사이 어디서 끊겨도 목은 시킨 대로 답한다.
 *
 * <p><b>브로커는 태우지 않는다.</b> 여기서 확인하려는 것은 원장 트랜잭션과 발행 페이로드이지
 * Kafka 배달이 아니다(그건 {@code KafkaOutboxIntegrationTest} 가 이미 컨테이너로 검증한다).
 * 리스너 배선 자체 — 토픽·컨슈머 그룹 — 는 {@link MarketingRewardConsumerTest} 가 애노테이션으로 못 박는다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@SpringBootTest(
        classes = github.lms.lemuel.LemuelApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "spring.batch.job.enabled=false"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MarketingRewardRoundTripIT {

    private static final String IN_TOPIC = "lemuel.marketing.reward_requested";
    private static final String OUT_TOPIC = "lemuel.point.granted";
    private static final String REWARD_ID = "3f1b5c9a-2d64-4f0e-9a71-8c5e2b7d1f40";
    private static final long USER_ID = 42L;

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("inter")
            .withUsername("lemuel")
            .withPassword("lemuel");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired ProcessedEventRepository processedEvents;
    @Autowired GrantPointUseCase grantPoint;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired @Qualifier("outboxObjectMapper") ObjectMapper outboxMapper;

    /**
     * 리스너 빈은 {@code app.kafka.enabled=true} 일 때만 등록된다. 브로커 없이 그 조건을 켜면
     * 컨슈머 컨테이너가 붙을 곳을 찾아 헤매므로, 여기서는 같은 협력자로 직접 조립한다 —
     * 원장에 닿는 경로는 운영과 동일하다(목이 하나도 없다).
     */
    private MarketingRewardConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MarketingRewardConsumer(processedEvents, outboxMapper, grantPoint);
        // 한 문장으로 자른다 — 원장 테이블은 FK 로 엮여 있어 DELETE 로는 순서를 맞춰야 하고,
        // 순서가 하나 틀리면 정리 단계에서 죽어 정작 검증은 시작도 못 한다.
        jdbc.execute("""
                TRUNCATE TABLE opslab.outbox_events,
                               opslab.processed_events,
                               opslab.point_lot_consumptions,
                               opslab.point_entries,
                               opslab.point_lots,
                               opslab.point_accounts
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    @DisplayName("보상 요청 한 건이 로트·원장 엔트리·되돌아갈 이벤트를 만든다")
    void rewardRequestBecomesLedgerGrantAndReturnEvent() {
        deliver(EventContractValidator.canonicalSample(IN_TOPIC), UUID.randomUUID());

        Map<String, Object> lot = jdbc.queryForMap("""
                SELECT origin, original_amount, reference_type, reference_id, expires_at
                  FROM opslab.point_lots
                """);
        assertThat(lot.get("origin")).isEqualTo("PROMOTION_REWARD");
        assertThat((BigDecimal) lot.get("original_amount")).isEqualByComparingTo("1000");
        assertThat(lot.get("reference_type")).isEqualTo("ATTENDANCE_GOAL");
        assertThat(lot.get("reference_id")).isEqualTo(REWARD_ID);
        assertThat(lot.get("expires_at")).isNotNull();

        assertThat(count("opslab.point_entries")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available FROM opslab.point_accounts WHERE user_id = ?", BigDecimal.class, USER_ID))
                .isEqualByComparingTo("1000");

        // 반환 이벤트 — 이게 marketing 의 PointGrantedConsumer 입력이 된다.
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT aggregate_type, event_type, status, payload::text AS payload
                  FROM opslab.outbox_events
                """);
        assertThat(event.get("aggregate_type")).isEqualTo("Point");
        assertThat(event.get("event_type")).isEqualTo("PointGranted");
        assertThat(event.get("status")).isEqualTo("PENDING");

        String payload = (String) event.get("payload");
        EventContractValidator.assertValid(OUT_TOPIC, payload);
        assertThat(field(payload, "referenceType")).isEqualTo("ATTENDANCE_GOAL");
        assertThat(field(payload, "referenceId")).isEqualTo(REWARD_ID);
        assertThat(field(payload, "origin")).isEqualTo("PROMOTION_REWARD");
        assertThat(field(payload, "amount")).isEqualTo("1000");
    }

    @Test
    @DisplayName("같은 요청이 두 번 도착해도 포인트는 한 번만 들어간다 — 두 겹으로")
    void redeliveryDoesNotDoubleGrant() {
        UUID eventId = UUID.randomUUID();

        deliver(EventContractValidator.canonicalSample(IN_TOPIC), eventId);
        // 1겹: 같은 event_id 재전달 (브로커 at-least-once)
        deliver(EventContractValidator.canonicalSample(IN_TOPIC), eventId);
        // 2겹: 다른 event_id, 같은 보상 (marketing 정산 스케줄러의 재요청 — event_id 가 새 값이다)
        deliver(EventContractValidator.canonicalSample(IN_TOPIC), UUID.randomUUID());

        assertThat(count("opslab.point_lots")).isEqualTo(1);
        assertThat(count("opslab.point_entries")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available FROM opslab.point_accounts WHERE user_id = ?", BigDecimal.class, USER_ID))
                .isEqualByComparingTo("1000");

        // 두 번째 겹이 막은 요청은 이벤트도 내지 않는다. 냈다면 marketing 이 확정을 두 번 받는다.
        assertThat(count("opslab.outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("돌아가는 참조는 marketing 이 자기 것으로 알아보는 모양이다")
    void returnedReferenceIsRecognizableByMarketing() {
        deliver(EventContractValidator.canonicalSample(IN_TOPIC), UUID.randomUUID());

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM opslab.outbox_events", String.class);

        // marketing 의 판정 기준을 그대로 옮겨 적는다 — referenceType 이 보상 종류 이름이고
        // referenceId 가 UUID 여야 한다. 둘 중 하나만 어긋나도 보상은 미확정으로 남는다.
        assertThat(List.of("ATTENDANCE_DAILY", "ATTENDANCE_GOAL", "LUCKYBOX"))
                .contains(field(payload, "referenceType"));
        assertThat(UUID.fromString(field(payload, "referenceId"))).hasToString(REWARD_ID);
    }

    @Test
    @DisplayName("로트 출처 CHECK 제약은 PointLotOrigin 값을 전부 허용한다")
    void originCheckConstraintCoversEveryEnumValue() {
        // 출처 목록이 자바 열거형과 DB CHECK 두 곳에 적혀 있다. 한쪽만 늘리면 컴파일러도 단위
        // 테스트도 침묵하고, 그 출처의 적립만 운영에서 insert 거절로 죽는다 — PROMOTION_REWARD 가
        // 실제로 그랬다. 다음 값이 추가될 때 같은 일이 반복되지 않도록 실 스키마에 대고 묶어 둔다.
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'chk_point_lots_origin'
                """, String.class);

        assertThat(definition).isNotNull();
        for (PointLotOrigin origin : PointLotOrigin.values()) {
            assertThat(definition)
                    .as("CHECK 제약에 %s 가 빠져 있다 — 이 출처의 적립은 전부 거절된다", origin)
                    .contains("'" + origin.name() + "'");
        }
    }

    // ---------------------------------------------------------------- 도구

    /** 리스너의 트랜잭션 경계를 그대로 흉내 낸다 — ack 는 커밋 이후로 미뤄진다. */
    private void deliver(String payload, UUID eventId) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(IN_TOPIC, 0, 0L, REWARD_ID, payload);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        tx.executeWithoutResult(status -> consumer.onRewardRequested(record, ack));
        Mockito.verify(ack).acknowledge();
    }

    private String field(String payload, String name) {
        try {
            return outboxMapper.readTree(payload).path(name).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
