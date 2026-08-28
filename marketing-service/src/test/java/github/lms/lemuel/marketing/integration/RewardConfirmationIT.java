package github.lms.lemuel.marketing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import github.lms.lemuel.MarketingServiceApplication;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.marketing.adapter.in.kafka.PointGrantedConsumer;
import github.lms.lemuel.marketing.application.port.in.ConfirmRewardUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.admin;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.member;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.today;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.truncateAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보상 왕복의 <b>마케팅 쪽 반</b> — 요청을 내고, 돌아온 통지로 그 요청을 닫는 데까지.
 *
 * <pre>
 *   출석 체크 → reward_grants(REQUESTED) + outbox(RewardRequested)
 *                                              ↓ (원장이 적립하고 되돌려 보낸다)
 *   lemuel.point.granted → PointGrantedConsumer → reward_grants(CONFIRMED)
 * </pre>
 *
 * <p>여기서 재는 것은 <b>왕복이 실제로 닫히는가</b>다. 두 다리를 따로 시험하면 각각은 초록인데
 * 가운데가 어긋날 수 있다 — 우리가 outbox 에 싣는 {@code rewardId} 와, 돌아온 이벤트에서 우리가
 * 읽는 {@code referenceId} 가 같은 값이어야 한다는 것을 어느 쪽 테스트도 혼자서는 말하지 못한다.
 * 어긋나면 포인트는 들어갔는데 우리 장부는 영영 REQUESTED 다. 예외도 로그도 남지 않는다.
 *
 * <p>그래서 이 테스트는 확정에 쓸 페이로드를 손으로 적지 않고 <b>우리가 방금 낸 outbox 행에서
 * 뽑아 만든다</b>. 원장이 하는 일(참조를 그대로 되돌려 보내는 것)만 흉내 낸다.
 */
@SpringBootTest(
        classes = MarketingServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.marketing.settlement.enabled=false"
        }
)
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf(value = "github.lms.lemuel.marketing.integration.MarketingIntegrationSupport#isDockerAvailable",
        disabledReason = "Docker is not available")
class RewardConfirmationIT {

    private static final String GRANTED_TOPIC = "lemuel.point.granted";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("marketing_test").withUsername("test").withPassword("test");

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
    JdbcTemplate jdbc;
    @Autowired
    ProcessedEventRepository processedEvents;
    @Autowired
    ConfirmRewardUseCase confirmReward;
    @Autowired
    TransactionTemplate tx;

    private final ObjectMapper mapper = OutboxJson.mapper();

    /**
     * 리스너 빈은 {@code app.kafka.enabled=true} 에서만 등록된다. 브로커 없이 그 조건을 켜면
     * 컨슈머 컨테이너가 붙을 곳을 찾아 헤매므로, 같은 협력자로 직접 조립한다 —
     * 확정에 닿는 경로는 운영과 동일하다(목이 하나도 없다).
     */
    private PointGrantedConsumer consumer;

    @BeforeEach
    void reset() {
        consumer = new PointGrantedConsumer(processedEvents, mapper, confirmReward);
        jdbc.execute(truncateAll());
    }

    @Test
    @DisplayName("요청한 보상이 돌아온 적립 통지로 확정된다 — 왕복이 닫힌다")
    void requestedRewardIsConfirmedByReturnedGrant() throws Exception {
        checkIn(openedCampaign("왕복 확정"), 4242L);

        Map<String, Object> requested = jdbc.queryForMap(
                "SELECT id, source, status, confirmed_at FROM marketing.reward_grants");
        assertThat(requested.get("status")).isEqualTo("REQUESTED");
        assertThat(requested.get("confirmed_at")).isNull();

        deliverGrantEchoedFromOutbox(UUID.randomUUID());

        Map<String, Object> confirmed = jdbc.queryForMap(
                "SELECT status, confirmed_at FROM marketing.reward_grants");
        assertThat(confirmed.get("status")).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("confirmed_at")).isNotNull();
    }

    @Test
    @DisplayName("같은 통지가 두 번 와도 확정 시각은 처음 것 그대로다")
    void redeliveryDoesNotMoveConfirmedAt() throws Exception {
        checkIn(openedCampaign("재전달"), 77L);

        UUID eventId = UUID.randomUUID();
        deliverGrantEchoedFromOutbox(eventId);
        Object firstConfirmedAt = jdbc.queryForObject(
                "SELECT confirmed_at FROM marketing.reward_grants", Object.class);

        // 같은 event_id 재전달(브로커 at-least-once)과 새 event_id 재전달(원장 재발행) 둘 다.
        deliverGrantEchoedFromOutbox(eventId);
        deliverGrantEchoedFromOutbox(UUID.randomUUID());

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT status, confirmed_at FROM marketing.reward_grants");
        assertThat(after.get("status")).isEqualTo("CONFIRMED");
        // 확정 시각이 밀리면 "언제 지급됐나" 질문에 매번 다른 답이 나온다.
        assertThat(after.get("confirmed_at")).isEqualTo(firstConfirmedAt);
    }

    @Test
    @DisplayName("남의 적립 통지는 우리 보상을 건드리지 않는다")
    void foreignGrantLeavesOurRewardsAlone() throws Exception {
        checkIn(openedCampaign("남의 적립"), 88L);

        // 정본 샘플은 충전 보너스(referenceType=CHARGE)다. 이 토픽 트래픽의 대부분이 이런 것들이다.
        String sample = EventContractValidator.canonicalSample(GRANTED_TOPIC);
        EventContractValidator.assertValid(GRANTED_TOPIC, sample);
        deliver(sample, UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT status FROM marketing.reward_grants", String.class))
                .isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("우리 종류인데 모르는 보상 id 면 조용히 지나간다 — 터뜨리지 않는다")
    void unknownRewardIdIsIgnored() throws Exception {
        checkIn(openedCampaign("모르는 id"), 99L);

        deliver(grantedPayload("ATTENDANCE_DAILY", UUID.randomUUID().toString()), UUID.randomUUID());

        // 터졌다면 ack 되지 않고 재전달되다 DLQ 로 간다 — 우리 것도 아닌 이벤트 때문에.
        assertThat(jdbc.queryForObject("SELECT status FROM marketing.reward_grants", String.class))
                .isEqualTo("REQUESTED");
    }

    // ---------------------------------------------------------------- 도구

    /**
     * 우리가 방금 낸 outbox 행에서 참조를 뽑아, 원장이 되돌려 보낼 통지를 만든다.
     * 손으로 적은 JSON 을 쓰면 이 짝이 어긋나도 테스트는 초록이다 — 그게 이 테스트의 요점이다.
     */
    private void deliverGrantEchoedFromOutbox(UUID eventId) {
        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT payload ->> 'rewardId' AS reward_id,
                       payload ->> 'source'   AS source
                  FROM marketing.outbox_events
                 WHERE event_type = 'RewardRequested'
                """);
        deliver(grantedPayload((String) outbox.get("source"), (String) outbox.get("reward_id")), eventId);
    }

    private String grantedPayload(String referenceType, String referenceId) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(
                    EventContractValidator.canonicalSample(GRANTED_TOPIC));
            node.put("origin", "PROMOTION_REWARD");
            node.put("referenceType", referenceType);
            node.put("referenceId", referenceId);
            String payload = mapper.writeValueAsString(node);
            // 우리가 만들어 넣은 입력이 계약을 벗어나면 아래 판정이 무의미해진다.
            EventContractValidator.assertValid(GRANTED_TOPIC, payload);
            return payload;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 리스너의 트랜잭션 경계를 그대로 흉내 낸다 — ack 는 커밋 이후로 미뤄진다. */
    private void deliver(String payload, UUID eventId) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(GRANTED_TOPIC, 0, 0L, "key", payload);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        tx.executeWithoutResult(status -> consumer.onPointGranted(record, ack));
        Mockito.verify(ack).acknowledge();
    }

    private void checkIn(String campaignId, long userId) throws Exception {
        mockMvc.perform(post("/api/promotions/attendance/check-in")
                        .param("campaignId", campaignId)
                        .with(member(userId)))
                .andExpect(status().isOk());
    }

    private String openedCampaign(String name) throws Exception {
        LocalDate today = today();
        String body = """
                {
                  "tenantRef": "lemuel",
                  "name": "%s",
                  "periodType": "MONTHLY",
                  "startsOn": "%s",
                  "endsOn": "%s",
                  "streakRule": "CUMULATIVE",
                  "requiredCount": 30,
                  "dayTypeRule": "EVERY_DAY",
                  "dailyRewardPoints": 10,
                  "goalRewardPoints": 500,
                  "rewardExpiresOn": "%s",
                  "pcImageUrl": "https://cdn.lemuel.test/pc.png",
                  "mobileImageUrl": "https://cdn.lemuel.test/mo.png",
                  "messageRunning": "출석하고 포인트 받아 가세요"
                }
                """.formatted(name, today.minusDays(3), today.plusDays(30), today.plusDays(90));

        String json = mockMvc.perform(post("/admin/promotions/attendance")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = json.replaceAll(".*\"campaignId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(id).isNotEqualTo(json);

        mockMvc.perform(post("/admin/promotions/attendance/{id}/open", id).with(admin()))
                .andExpect(status().isNoContent());
        return id;
    }
}
