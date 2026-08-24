package github.lms.lemuel.organization.integration;

import github.lms.lemuel.LemuelApplication;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.organization.adapter.out.persistence.SpringDataMembershipRepository;
import github.lms.lemuel.organization.adapter.out.persistence.SpringDataOrganizationRepository;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase.InviteCommand;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조직관리 종단 통합 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway(opslab) + 실 시큐리티 체인.
 *
 * <ol>
 *   <li>생성 → 초대 → 수락 라이프사이클 + Outbox 이벤트(created/member_joined) 적재</li>
 *   <li>인가 — 비멤버 403, 무인증 401, STAFF 초대 시도 403</li>
 *   <li>마지막 OWNER 강등/제거 차단(422)</li>
 *   <li>동시 초대 경쟁 → 활성 멤버십 슬롯 1건(uq_membership_active)</li>
 * </ol>
 */
@SpringBootTest(
        classes = LemuelApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
// order-service 의 application.yml 은 ${JWT_ISSUER}·${JWT_TTL_SECONDS} 에 기본값이 없다
// (운영에서 env 주입 강제). test 프로파일이 그 플레이스홀더의 안전한 기본값을 공급한다 —
// 실 DB 는 아래 @DynamicPropertySource 가 Testcontainers 로 덮어쓴다.
// organization 이 독립 서비스였을 때는 자체 yml 에 기본값이 있어 필요 없었다(ADR 0042 이관).
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class OrganizationLifecycleIntegrationTest {

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
            .withDatabaseName("organization_test").withUsername("test").withPassword("test");

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
    MembershipCommandUseCase membershipCommandUseCase;
    @Autowired
    SpringDataOrganizationRepository organizationRepository;
    @Autowired
    SpringDataMembershipRepository membershipRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        membershipRepository.deleteAll();
        organizationRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM opslab.outbox_events");
    }

    private static RequestPostProcessor asUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@lemuel", "USER"),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private long createOrgAsOwner(long ownerUserId) throws Exception {
        mockMvc.perform(post("/api/organizations").with(asUser(ownerUserId))
                        .contentType("application/json")
                        .content("{\"name\":\"무신사\",\"type\":\"SELLER\",\"externalRef\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
        return organizationRepository.findAll().getFirst().getId();
    }

    private int outboxCount(String eventType) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = ?", Integer.class, eventType);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("시나리오1: 생성 → 초대 → 수락 + Outbox 이벤트 적재")
    void createInviteAccept_lifecycle() throws Exception {
        long orgId = createOrgAsOwner(100L);
        assertThat(outboxCount("OrganizationCreated")).isEqualTo(1);

        // OWNER(100) 이 STAFF 로 200 초대
        mockMvc.perform(post("/api/organizations/{id}/members", orgId).with(asUser(100L))
                        .contentType("application/json")
                        .content("{\"targetUserId\":200,\"role\":\"STAFF\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("INVITED")));

        // 200 이 수락
        mockMvc.perform(post("/api/organizations/{id}/members/accept", orgId).with(asUser(200L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
        assertThat(outboxCount("OrganizationMemberJoined")).isEqualTo(1);

        // 조직 조회 — 멤버 2명
        mockMvc.perform(get("/api/organizations/{id}", orgId).with(asUser(100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()", is(2)));
    }

    @Test
    @DisplayName("시나리오2: 인가 — 비멤버 403, 무인증 401, STAFF 초대 403")
    void authorization() throws Exception {
        long orgId = createOrgAsOwner(100L);

        // 비멤버(999) 조회 → 403
        mockMvc.perform(get("/api/organizations/{id}", orgId).with(asUser(999L)))
                .andExpect(status().isForbidden());

        // 무인증 조회 → 401
        mockMvc.perform(get("/api/organizations/{id}", orgId))
                .andExpect(status().isUnauthorized());

        // STAFF(200) 초대 후 수락 → STAFF 가 초대 시도 → 403
        membershipCommandUseCase.invite(new InviteCommand(orgId, 200L, OrgRole.STAFF, 100L));
        membershipCommandUseCase.accept(orgId, 200L);
        mockMvc.perform(post("/api/organizations/{id}/members", orgId).with(asUser(200L))
                        .contentType("application/json")
                        .content("{\"targetUserId\":300,\"role\":\"STAFF\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("시나리오3: 마지막 OWNER 강등/제거 차단(422)")
    void lastOwnerProtected() throws Exception {
        long orgId = createOrgAsOwner(100L);

        mockMvc.perform(patch("/api/organizations/{id}/members/{uid}/role", orgId, 100L).with(asUser(100L))
                        .contentType("application/json")
                        .content("{\"newRole\":\"STAFF\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(delete("/api/organizations/{id}/members/{uid}", orgId, 100L).with(asUser(100L)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("시나리오4: 동시 초대 경쟁 — 활성 멤버십 슬롯 1건")
    void concurrentInvite_singleActiveSlot() throws Exception {
        long orgId = createOrgAsOwner(100L);

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        membershipCommandUseCase.invite(new InviteCommand(orgId, 300L, OrgRole.STAFF, 100L));
                        succeeded.incrementAndGet();
                    } catch (Exception ignored) {
                        // 경쟁에서 진 스레드: DuplicateMembership 또는 uq 위반(DataIntegrity)
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long activeSlots = membershipRepository.findByOrganizationId(orgId).stream()
                .map(e -> e.toDomain())
                .filter(m -> m.getUserId().equals(300L) && m.occupiesActiveSlot())
                .count();
        assertThat(activeSlots).isEqualTo(1);
        assertThat(succeeded.get()).isGreaterThanOrEqualTo(1);
    }
}
