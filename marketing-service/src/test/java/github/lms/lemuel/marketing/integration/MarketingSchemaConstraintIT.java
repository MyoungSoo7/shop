package github.lms.lemuel.marketing.integration;

import github.lms.lemuel.MarketingServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.today;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.truncateAll;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1 마이그레이션이 약속한 제약이 실제로 걸려 있는지 확인한다.
 *
 * <p>제약은 적어 두는 것만으로는 아무 일도 하지 않는다. {@code CHECK} 절에 오타가 있어도, 유니크
 * 인덱스가 다른 컬럼 조합으로 만들어져 있어도 애플리케이션은 평소처럼 뜨고 단위 테스트도 초록이다.
 * 그 사실은 <b>잘못된 행을 실제로 밀어 넣어 봐야만</b> 드러난다.
 *
 * <p>애플리케이션 코드를 거치지 않고 JDBC 로 직접 넣는 것이 요점이다. 서비스 계층을 통과시키면
 * 도메인 검증이 먼저 막아 버려서 DB 가 무엇을 막고 있는지는 여전히 알 수 없다. 여기서 확인하는
 * 방어선은 <i>마지막</i> 방어선이다 — 운영 콘솔의 직접 수정, 데이터 이관 스크립트, 새로 붙는
 * 어댑터처럼 도메인을 우회하는 경로가 언제든 생기기 때문이다.
 */
@SpringBootTest(
        classes = MarketingServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.marketing.settlement.enabled=false"
        }
)
@Testcontainers
@EnabledIf(value = "github.lms.lemuel.marketing.integration.MarketingIntegrationSupport#isDockerAvailable",
        disabledReason = "Docker is not available")
class MarketingSchemaConstraintIT {

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
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute(truncateAll());
    }

    @Test
    @DisplayName("포인트 경품인데 금액이 없으면 DB 가 거절한다")
    void pointPrizeRequiresAmount() {
        UUID campaignId = insertLuckyboxCampaign("IMMEDIATE", null);

        assertThatThrownBy(() -> insertPrize(campaignId, "POINT", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 문구 경품은 금액이 없어도 정상이다 — 제약이 과하게 걸려 있지 않다는 확인이다.
        assertThatCode(() -> insertPrize(campaignId, "TEXT", null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("음수 수량은 DB 가 거절한다")
    void negativeQuotaRejected() {
        UUID campaignId = insertLuckyboxCampaign("IMMEDIATE", null);

        assertThatThrownBy(() -> insertPrize(campaignId, "POINT", BigDecimal.TEN, -1))
                .isInstanceOf(DataIntegrityViolationException.class);

        // NULL 은 무제한. 레거시가 0 을 무제한과 소진 양쪽으로 쓰던 자리다.
        assertThatCode(() -> insertPrize(campaignId, "POINT", BigDecimal.TEN, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("일괄 지급 캠페인인데 지급일이 없으면 DB 가 거절한다")
    void batchCampaignRequiresBenefitDate() {
        // 이게 통과되면 화면엔 "당첨" 이 뜨고 포인트만 영원히 안 들어온다.
        assertThatThrownBy(() -> insertLuckyboxCampaign("BATCH", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> insertLuckyboxCampaign("BATCH", today().plusDays(7)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한 참여 기록에 보상은 한 건뿐이다")
    void oneRewardPerReference() {
        UUID referenceId = UUID.randomUUID();
        insertRewardGrant("LUCKYBOX", referenceId, "100");

        // 컨슈머 재시도·중복 요청이 두 번 적립하는 경로를 구조적으로 없앤 제약.
        assertThatThrownBy(() -> insertRewardGrant("LUCKYBOX", referenceId, "100"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 원본이 같아도 종류가 다르면 별개다 — 출석 일일 보상과 목표 보상이 같은 날 함께 나간다.
        assertThatCode(() -> insertRewardGrant("ATTENDANCE_GOAL", referenceId, "500"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("0 포인트 보상은 애초에 저장되지 않는다")
    void zeroAmountRewardRejected() {
        assertThatThrownBy(() -> insertRewardGrant("LUCKYBOX", UUID.randomUUID(), "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 슬롯의 중복 참여는 인덱스가 막는다")
    void duplicateEntrySlotRejected() {
        UUID campaignId = insertLuckyboxCampaign("IMMEDIATE", null);
        insertDraw(campaignId, "777", "ALL");

        assertThatThrownBy(() -> insertDraw(campaignId, "777", "ALL"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 슬롯이 다르면(PER_DAY 캠페인의 다른 날) 통과한다.
        assertThatCode(() -> insertDraw(campaignId, "777", today().toString()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 날 두 번 출석한 행은 들어가지 않는다")
    void duplicateAttendanceRejected() {
        UUID campaignId = insertAttendanceCampaign();
        LocalDate on = today();
        insertAttendanceRecord(campaignId, "555", on);

        assertThatThrownBy(() -> insertAttendanceRecord(campaignId, "555", on))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> insertAttendanceRecord(campaignId, "555", on.plusDays(1)))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 도구

    private UUID insertLuckyboxCampaign(String benefitType, LocalDate benefitOn) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO marketing.luckybox_campaigns
                    (id, tenant_ref, name, starts_on, ends_on, status, benefit_type, benefit_on, entry_condition)
                VALUES (?, 'lemuel', '제약 검증', ?, ?, 'DRAFT', ?, ?, 'PER_PERIOD')
                """, id, Date.valueOf(today()), Date.valueOf(today().plusDays(7)), benefitType,
                benefitOn == null ? null : Date.valueOf(benefitOn));
        return id;
    }

    private void insertPrize(UUID campaignId, String prizeType, BigDecimal points, Integer totalQuota) {
        jdbc.update("""
                INSERT INTO marketing.luckybox_prizes
                    (id, campaign_id, prize_type, reward_points, text_reward, total_quota, win_rate)
                VALUES (?, ?, ?, ?, '문구', ?, 1.0)
                """, UUID.randomUUID(), campaignId, prizeType, points, totalQuota);
    }

    private void insertDraw(UUID campaignId, String memberRef, String slot) {
        jdbc.update("""
                INSERT INTO marketing.luckybox_draws
                    (id, campaign_id, member_ref, prize_type, text_reward, drawn_on, entry_slot)
                VALUES (?, ?, ?, 'TEXT', '꽝', ?, ?)
                """, UUID.randomUUID(), campaignId, memberRef, Date.valueOf(today()), slot);
    }

    private void insertRewardGrant(String source, UUID referenceId, String amount) {
        jdbc.update("""
                INSERT INTO marketing.reward_grants
                    (id, source, reference_id, campaign_id, member_ref, amount, status)
                VALUES (?, ?, ?, ?, '1', ?, 'PENDING')
                """, UUID.randomUUID(), source, referenceId, UUID.randomUUID(), new BigDecimal(amount));
    }

    private UUID insertAttendanceCampaign() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO marketing.attendance_campaigns
                    (id, tenant_ref, name, period_type, starts_on, ends_on, status,
                     streak_rule, required_count, day_type_rule)
                VALUES (?, 'lemuel', '제약 검증 출석', 'MONTHLY', ?, ?, 'RUNNING', 'CUMULATIVE', 5, 'EVERY_DAY')
                """, id, Date.valueOf(today()), Date.valueOf(today().plusDays(30)));
        return id;
    }

    private void insertAttendanceRecord(UUID campaignId, String memberRef, LocalDate on) {
        jdbc.update("""
                INSERT INTO marketing.attendance_records
                    (id, campaign_id, member_ref, attended_on, daily_reward_points,
                     campaign_name_snapshot, streak_rule_snapshot, period_start_snapshot, period_end_snapshot)
                VALUES (?, ?, ?, ?, 10, '제약 검증 출석', 'CUMULATIVE', ?, ?)
                """, UUID.randomUUID(), campaignId, memberRef, Date.valueOf(on),
                Date.valueOf(today()), Date.valueOf(today().plusDays(30)));
    }
}
