package github.lms.lemuel.marketing.integration;

import github.lms.lemuel.MarketingServiceApplication;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxPrizeCommand;
import github.lms.lemuel.marketing.application.port.in.DrawLuckyboxUseCase;
import github.lms.lemuel.marketing.application.port.in.ManageLuckyboxCampaignUseCase;
import github.lms.lemuel.marketing.application.port.out.LuckyboxPrizePort;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.PrizeType;
import github.lms.lemuel.marketing.domain.exception.NoPrizeAvailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.today;
import static github.lms.lemuel.marketing.integration.MarketingIntegrationSupport.truncateAll;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 럭키박스 수량 제한 — 실 PostgreSQL 에서만 증명되는 것들.
 *
 * <p>레거시에는 {@code // 아이템 수량 확인} 주석 아래가 비어 있었다. 그래서 "선착순 100명" 경품이
 * 몇 명에게 나갔는지 아무도 몰랐고, 재고가 0 인 경품도 계속 당첨됐다. 대체 구현은 조건부 UPDATE
 * 한 문장({@code tryReserve})으로 뽑기와 차감을 합쳤는데, <b>그게 정말로 동시성에서 버티는지는
 * 목(mock)으로는 확인할 수 없다</b> — 목은 언제나 시키는 대로 성공하거나 실패한다.
 *
 * <p>여기서 재는 것은 두 가지다.
 *
 * <ol>
 *   <li>동시에 열 명이 뽑을 때 수량 3 짜리 경품이 정확히 세 명에게만 나가는가</li>
 *   <li>일일 수량 카운터가 날짜가 바뀌면 리셋되는가 — {@code CASE WHEN daily_issued_date = :today}
 *       는 SQL 안에서만 도는 분기라 자바 테스트로는 건드릴 수 없다</li>
 * </ol>
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
class LuckyboxQuotaConcurrencyIT {

    private static final int CONTENDERS = 10;
    private static final int TOTAL_QUOTA = 3;

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
    ManageLuckyboxCampaignUseCase admin;
    @Autowired
    DrawLuckyboxUseCase draws;
    @Autowired
    LuckyboxPrizePort prizePort;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute(truncateAll());
    }

    @Test
    @DisplayName("수량 3 짜리 경품에 열 명이 동시에 달려들어도 정확히 셋만 받는다")
    void totalQuotaHoldsUnderConcurrency() throws Exception {
        LocalDate on = today();
        UUID campaignId = draftCampaign("선착순 럭키박스", on);
        // 경품이 하나뿐이므로 가중치 추첨 결과는 항상 이 경품이다 — 수량 판정만 남는다.
        admin.addPrize(new CreateLuckyboxPrizeCommand(campaignId, PrizeType.POINT, new BigDecimal("100"),
                null, TOTAL_QUOTA, null, new BigDecimal("1.0"), 0, "admin@lemuel.test"));
        open(campaignId);

        AtomicInteger won = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONTENDERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        try {
            for (int i = 0; i < CONTENDERS; i++) {
                String memberRef = String.valueOf(9000 + i);
                pool.submit(() -> {
                    try {
                        start.await();
                        draws.draw(campaignId, memberRef, on);
                        won.incrementAndGet();
                    } catch (NoPrizeAvailableException e) {
                        soldOut.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 초과 지급이 0 이라는 것만으로는 부족하다 — 전부 실패해도 초과는 0 이다. 정확히 셋이어야 한다.
        assertThat(unexpected.get()).isZero();
        assertThat(won.get()).isEqualTo(TOTAL_QUOTA);
        assertThat(soldOut.get()).isEqualTo(CONTENDERS - TOTAL_QUOTA);

        assertThat(count("marketing.luckybox_draws")).isEqualTo(TOTAL_QUOTA);
        assertThat(count("marketing.reward_grants")).isEqualTo(TOTAL_QUOTA);

        Map<String, Object> prize = jdbc.queryForMap(
                "SELECT issued_count, daily_issued_count, daily_issued_date FROM marketing.luckybox_prizes");
        assertThat(((Number) prize.get("issued_count")).intValue()).isEqualTo(TOTAL_QUOTA);
        assertThat(((Number) prize.get("daily_issued_count")).intValue()).isEqualTo(TOTAL_QUOTA);
        assertThat(((Date) prize.get("daily_issued_date")).toLocalDate()).isEqualTo(on);

        // 당첨자마다 보상 한 건. 중복 지급 경로가 없다는 뜻이다.
        List<String> members = jdbc.queryForList(
                "SELECT DISTINCT member_ref FROM marketing.reward_grants", String.class);
        assertThat(members).hasSize(TOTAL_QUOTA);
    }

    @Test
    @DisplayName("일일 수량은 날짜가 바뀌면 리셋된다")
    void dailyQuotaResetsOnDateChange() {
        LocalDate day1 = today();
        LocalDate day2 = day1.plusDays(1);
        UUID campaignId = draftCampaign("매일 한 개", day1);
        UUID prizeId = admin.addPrize(new CreateLuckyboxPrizeCommand(campaignId, PrizeType.POINT,
                new BigDecimal("50"), null, null, 1, new BigDecimal("1.0"), 0, "admin@lemuel.test"));
        open(campaignId);

        assertThat(reserve(prizeId, day1)).isTrue();
        // 같은 날 두 번째는 일일 수량에 막힌다.
        assertThat(reserve(prizeId, day1)).isFalse();
        // 날짜가 넘어가면 카운터가 1 로 되돌아간다 — 날짜별 행 없이 CASE 한 줄로 처리한 부분.
        assertThat(reserve(prizeId, day2)).isTrue();
        assertThat(reserve(prizeId, day2)).isFalse();

        Map<String, Object> prize = jdbc.queryForMap(
                "SELECT issued_count, daily_issued_count, daily_issued_date FROM marketing.luckybox_prizes");
        assertThat(((Number) prize.get("issued_count")).intValue()).isEqualTo(2);
        assertThat(((Number) prize.get("daily_issued_count")).intValue()).isEqualTo(1);
        assertThat(((Date) prize.get("daily_issued_date")).toLocalDate()).isEqualTo(day2);
    }

    // ---------------------------------------------------------------- 도구

    /** {@code tryReserve} 는 조건부 UPDATE 라 트랜잭션 안에서만 돈다(운영에서는 draw 가 감싼다). */
    private boolean reserve(UUID prizeId, LocalDate on) {
        return Boolean.TRUE.equals(tx.execute(s -> prizePort.tryReserve(prizeId, on)));
    }

    private UUID draftCampaign(String name, LocalDate on) {
        return admin.create(new CreateLuckyboxCampaignCommand(
                "lemuel", name, on.minusDays(1), on.plusDays(7),
                BenefitType.IMMEDIATE, null, EntryCondition.PER_PERIOD, on.plusDays(90),
                "테스트 캠페인", null, null, "admin@lemuel.test"));
    }

    /**
     * 경품을 다 넣은 뒤에만 열 수 있다 — 빈 이벤트를 여는 건 도메인이 거절한다. 레거시는 이걸
     * 막지 않아서 "당첨 없음" 만 반복하는 이벤트가 실제로 열렸다.
     */
    private void open(UUID campaignId) {
        admin.open(campaignId, "admin@lemuel.test");
    }

    private int count(String fromClause) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromClause, Integer.class);
        return n == null ? 0 : n;
    }
}
