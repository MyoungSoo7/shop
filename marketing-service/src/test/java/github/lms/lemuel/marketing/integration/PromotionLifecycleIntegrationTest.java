package github.lms.lemuel.marketing.integration;

import github.lms.lemuel.MarketingServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.admin;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.member;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.today;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.truncateAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로모션 종단 통합 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway(marketing 스키마).
 *
 * <p>단위 테스트는 포트를 전부 가짜로 바꿔 놓고 돈다. 그래서 <b>매핑이 틀려도 초록</b>이다 —
 * 엔티티 컬럼명이 마이그레이션과 어긋나거나, 유니크 제약이 실제로는 안 걸려 있거나,
 * outbox 스키마가 {@code default_schema} 와 다른 곳을 보고 있어도 단위 테스트는 모른다.
 * 여기서 확인하는 건 그 층이다.
 *
 * <p>검증 축:
 * <ol>
 *   <li>DRAFT 는 고객 목록에 없다 → open 하면 나타난다 (레거시는 등록하는 순간 노출됐다)</li>
 *   <li>출석 한 번 = 기록 1 + 보상 1 + outbox 1, 한 트랜잭션에서</li>
 *   <li>같은 날 두 번째 출석은 유니크 제약이 막는다 (레거시의 select-then-insert 대체)</li>
 *   <li>목표를 달성하면 일일 보상과 목표 보상이 각각 따로 나간다</li>
 *   <li>보안 — 참여 주체는 JWT 에서만, 운영 API 는 ADMIN 만</li>
 * </ol>
 */
@SpringBootTest(
        classes = MarketingServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                // 정산 스케줄러가 테스트 도중 BATCH 보상을 집어가면 행 수 단언이 흔들린다.
                "app.marketing.settlement.enabled=false"
        }
)
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf(value = "github.lms.lemuel.marketing.integration.MarketingIntegrationSupport#isDockerAvailable",
        disabledReason = "Docker is not available")
class PromotionLifecycleIntegrationTest {

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

    @BeforeEach
    void reset() {
        jdbc.execute(truncateAll());
    }

    // ------------------------------------------------------------------ ①

    @Test
    @DisplayName("등록만 한 캠페인은 고객에게 보이지 않고, 열어야 보인다")
    void draftIsHiddenUntilOpened() throws Exception {
        String campaignId = createAttendanceCampaign("8월 출석 이벤트", 30);

        // 비로그인 목록 — 배너를 보려면 로그인이 필요하지 않다.
        mockMvc.perform(get("/api/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/admin/promotions/attendance/{id}/open", campaignId).with(admin()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(campaignId))
                .andExpect(jsonPath("$[0].name").value("8월 출석 이벤트"));

        assertThat(campaignStatus(campaignId)).isEqualTo("RUNNING");
    }

    // ------------------------------------------------------------------ ②

    @Test
    @DisplayName("출석 한 번에 기록·보상·아웃박스가 한 트랜잭션으로 남는다")
    void checkInWritesRecordRewardAndOutbox() throws Exception {
        String campaignId = openedAttendanceCampaign("일일 출석", 30);

        mockMvc.perform(post("/api/promotions/attendance/check-in")
                        .param("campaignId", campaignId)
                        .with(member(4242L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendedOn").value(today().toString()))
                .andExpect(jsonPath("$.attendedTotal").value(1))
                .andExpect(jsonPath("$.attendedStreak").value(1))
                .andExpect(jsonPath("$.goalReached").value(false))
                .andExpect(jsonPath("$.rewardPending").value(true));

        assertThat(count("marketing.attendance_records WHERE member_ref = '4242'")).isEqualTo(1);

        // 보상은 "요청됨" 까지만 간다. 포인트를 실제로 더하는 건 order-service 다.
        Map<String, Object> grant = jdbc.queryForMap(
                "SELECT id, source, member_ref, amount, status FROM marketing.reward_grants");
        assertThat(grant.get("source")).isEqualTo("ATTENDANCE_DAILY");
        assertThat(grant.get("member_ref")).isEqualTo("4242");
        assertThat(grant.get("status")).isEqualTo("REQUESTED");
        assertThat(((Number) grant.get("amount")).intValue()).isEqualTo(10);

        // 발행은 같은 트랜잭션의 outbox 행으로만 이뤄진다 — 카프카가 죽어도 이 행은 남는다.
        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_id, event_type, status, producer,
                       payload ->> 'rewardId'   AS reward_id,
                       payload ->> 'userId'     AS user_id,
                       payload ->> 'amount'     AS amount,
                       payload ->> 'source'     AS source
                  FROM marketing.outbox_events
                """);
        assertThat(outbox.get("aggregate_type")).isEqualTo("Marketing");
        assertThat(outbox.get("event_type")).isEqualTo("RewardRequested");
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(outbox.get("aggregate_id")).isEqualTo(String.valueOf(grant.get("id")));
        assertThat(outbox.get("reward_id")).isEqualTo(String.valueOf(grant.get("id")));
        // 회원 참조는 문자열이지만 페이로드의 userId 는 숫자여야 order-service 가 받는다.
        assertThat(outbox.get("user_id")).isEqualTo("4242");
        assertThat(outbox.get("source")).isEqualTo("ATTENDANCE_DAILY");
    }

    // ------------------------------------------------------------------ ③

    @Test
    @DisplayName("같은 날 두 번째 출석은 409 이고 보상도 늘지 않는다")
    void secondCheckInSameDayIsRejected() throws Exception {
        String campaignId = openedAttendanceCampaign("중복 방지", 30);

        mockMvc.perform(post("/api/promotions/attendance/check-in")
                        .param("campaignId", campaignId).with(member(7L)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/promotions/attendance/check-in")
                        .param("campaignId", campaignId).with(member(7L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARTICIPATED"));

        assertThat(count("marketing.attendance_records")).isEqualTo(1);
        assertThat(count("marketing.reward_grants")).isEqualTo(1);
        assertThat(count("marketing.outbox_events")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ④

    @Test
    @DisplayName("목표를 채우면 일일 보상과 목표 보상이 따로 나간다")
    void goalRewardIsIssuedSeparately() throws Exception {
        // requiredCount = 1 → 첫 출석이 곧 목표 달성이다.
        String campaignId = openedAttendanceCampaign("하루만 와도 목표", 1);

        mockMvc.perform(post("/api/promotions/attendance/check-in")
                        .param("campaignId", campaignId).with(member(11L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalReached").value(true));

        assertThat(count("marketing.attendance_achievements")).isEqualTo(1);

        List<String> sources = jdbc.queryForList(
                "SELECT source FROM marketing.reward_grants ORDER BY source", String.class);
        assertThat(sources).containsExactly("ATTENDANCE_DAILY", "ATTENDANCE_GOAL");
        assertThat(count("marketing.outbox_events WHERE event_type = 'RewardRequested'")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ ⑤

    @Test
    @DisplayName("참여는 인증이 필요하고 운영 API 는 ADMIN 만 들어온다")
    void securityMatrix() throws Exception {
        // 목록은 열려 있다 — 배너를 봐야 로그인할 마음이 생긴다.
        mockMvc.perform(get("/api/promotions")).andExpect(status().isOk());
        // 출석판은 "내" 상태라서 주체가 없으면 의미가 없다.
        mockMvc.perform(get("/api/promotions/attendance")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/promotions/attendance/check-in")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/promotions/luckybox/draw")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/promotions/attendance")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/promotions/attendance").with(member(3L))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/promotions/attendance").with(admin())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 도구

    private String openedAttendanceCampaign(String name, int requiredCount) throws Exception {
        String id = createAttendanceCampaign(name, requiredCount);
        mockMvc.perform(post("/admin/promotions/attendance/{id}/open", id).with(admin()))
                .andExpect(status().isNoContent());
        return id;
    }

    /**
     * 운영자로 출석 캠페인을 만든다. 본문에 {@code actor} 가 없다는 점이 중요하다 —
     * 감사에 남는 운영자는 JWT 에서만 나온다.
     */
    private String createAttendanceCampaign(String name, int requiredCount) throws Exception {
        LocalDate today = today();
        String body = """
                {
                  "tenantRef": "lemuel",
                  "name": "%s",
                  "periodType": "MONTHLY",
                  "startsOn": "%s",
                  "endsOn": "%s",
                  "streakRule": "CUMULATIVE",
                  "requiredCount": %d,
                  "dayTypeRule": "EVERY_DAY",
                  "dailyRewardPoints": 10,
                  "goalRewardPoints": 500,
                  "rewardExpiresOn": "%s",
                  "pcImageUrl": "https://cdn.lemuel.test/pc.png",
                  "mobileImageUrl": "https://cdn.lemuel.test/mo.png",
                  "messageRunning": "출석하고 포인트 받아 가세요"
                }
                """.formatted(name, today.minusDays(3), today.plusDays(30), requiredCount, today.plusDays(90));

        String json = mockMvc.perform(post("/admin/promotions/attendance")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String created = json.replaceAll(".*\"campaignId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(created).isNotEqualTo(json);
        assertThat(campaignStatus(created)).isEqualTo("DRAFT");
        return created;
    }

    private String campaignStatus(String campaignId) {
        return jdbc.queryForObject(
                "SELECT status FROM marketing.attendance_campaigns WHERE id = ?::uuid",
                String.class, campaignId);
    }

    private int count(String fromClause) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromClause, Integer.class);
        return n == null ? 0 : n;
    }
}
