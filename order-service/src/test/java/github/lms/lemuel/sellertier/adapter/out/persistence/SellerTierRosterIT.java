package github.lms.lemuel.sellertier.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerTierRosterPort.RawSellerRow;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort.RawDrift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 명부 조회와 정합 검사가 <b>실제 스키마</b>에서 무엇을 세는지 고정한다.
 *
 * <p>여기는 목으로 덮을 수 없는 자리다. 두 결함 모두 SQL 안에만 있었다 —
 * {@code users.seller_tier} 의 {@code NOT NULL DEFAULT 'NORMAL'} 이 FULL OUTER JOIN 과 만나
 * 셀러가 아닌 계정을 전부 드리프트로 세었고(운영에서 13건, 전부 거짓), 명부는 아예 없었다.
 * 목 어댑터는 두 경우 모두 통과한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class SellerTierRosterIT {

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
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired DataSource dataSource;

    private SellerTierPersistenceAdapter adapter;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        adapter = new SellerTierPersistenceAdapter(jdbc);
    }

    @Test
    @DisplayName("셀러가 아닌 계정은 정합 검사에 잡히지 않는다")
    void nonSellerAccountsAreNotCountedAsDrift() {
        // 이 테스트가 잡는 회귀. users.seller_tier 는 NOT NULL DEFAULT 'NORMAL' 이라 셀러가 아닌
        // 계정도 값을 갖는다. 이 조건을 조회에서 빼면 "정본 없음 vs 캐시 NORMAL" 로 전 사용자가
        // 드리프트가 되고, 거짓 경보가 진짜 불일치를 덮어 검사가 켜 두나 마나가 된다.
        Long nonSellers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM opslab.users u
                 WHERE u.seller_tier = 'NORMAL'
                   AND NOT EXISTS (SELECT 1 FROM opslab.seller_tier_assignment a WHERE a.seller_id = u.id)
                """, Long.class);
        assertThat(nonSellers).as("검사 대상이 실제로 존재해야 이 테스트가 무언가를 증명한다").isPositive();

        assertThat(adapter.countDrifts()).isZero();
        assertThat(adapter.findDrifts(50)).isEmpty();
    }

    @Test
    @DisplayName("정본과 캐시가 실제로 어긋나면 잡는다 — 좁힌 조건이 진짜 드리프트까지 지우지 않았는지")
    void realDriftIsStillCaught() {
        // 좁히는 수정의 위험은 잡아야 할 것까지 같이 지우는 것이다. 캐시만 손으로 되돌린 상황을
        // 만들어, 결제가 낡은 등급을 싣게 되는 바로 그 상태가 여전히 검출되는지 본다.
        Long sellerId = jdbc.queryForObject(
                "SELECT seller_id FROM opslab.seller_tier_assignment WHERE tier = 'VIP' LIMIT 1", Long.class);
        assertThat(sellerId).isNotNull();
        jdbc.update("UPDATE opslab.users SET seller_tier = 'NORMAL' WHERE id = ?", sellerId);

        assertThat(adapter.countDrifts()).isEqualTo(1L);
        List<RawDrift> drifts = adapter.findDrifts(50);
        assertThat(drifts).singleElement().satisfies(d -> {
            assertThat(d.sellerId()).isEqualTo(sellerId);
            assertThat(d.authoritativeTier()).isEqualTo("VIP");
            assertThat(d.cachedTier()).isEqualTo("NORMAL");
        });
    }

    @Test
    @DisplayName("정본이 없는데 캐시가 기본값이 아니면 여전히 잡는다 (수기 UPDATE 흔적)")
    void manuallyRaisedCacheWithoutAuthorityIsStillCaught() {
        Long orphan = jdbc.queryForObject("""
                SELECT u.id FROM opslab.users u
                 WHERE u.seller_tier = 'NORMAL'
                   AND NOT EXISTS (SELECT 1 FROM opslab.seller_tier_assignment a WHERE a.seller_id = u.id)
                 LIMIT 1
                """, Long.class);
        jdbc.update("UPDATE opslab.users SET seller_tier = 'STRATEGIC' WHERE id = ?", orphan);

        assertThat(adapter.findDrifts(50)).singleElement().satisfies(d -> {
            assertThat(d.sellerId()).isEqualTo(orphan);
            assertThat(d.authoritativeTier()).isNull();
            assertThat(d.cachedTier()).isEqualTo("STRATEGIC");
        });
    }

    @Test
    @DisplayName("명부는 상품을 가진 셀러를 등급·순매출과 함께 낸다")
    void rosterCarriesTierAndSalesForProductOwningSellers() {
        List<RawSellerRow> roster = adapter.findRoster(LocalDate.now(), 200);

        assertThat(roster).isNotEmpty();
        assertThat(roster).allSatisfy(row -> {
            assertThat(row.sellerId()).isNotNull();
            assertThat(row.email()).as("숫자 id 만으로는 누구인지 알 수 없다").isNotBlank();
            assertThat(row.cachedTier()).isNotNull();
            assertThat(row.netSales12m()).isNotNull();
        });
        assertThat(roster).anySatisfy(row -> assertThat(row.productCount()).isPositive());
        assertThat(roster).extracting(RawSellerRow::tier).contains("VIP", "STRATEGIC");
        assertThat(adapter.countSellers()).isEqualTo(roster.size());
    }

    @Test
    @DisplayName("정본이 없는 셀러도 명부에 등급 없음으로 나온다")
    void sellersWithoutAnAssignmentStillAppear() {
        // 관리자가 등급을 지정하려고 찾는 사람이 정확히 이들이다. 정본 테이블만 읽는 조회
        // (LoadTierAssignmentPort.findAll)로는 이 행이 존재하지 않아, 콘솔은 "지정할 대상"을
        // 영원히 보여주지 못한다 — 그래서 명부의 모집단이 상품까지 포함해야 한다.
        //
        // 시드에는 정본 없는 셀러가 없다(모든 시드 셀러가 assignment 를 갖는다). 그래서 상황을
        // 직접 만든다. 롤백되는 트랜잭션 안이라 시드는 그대로 남는다.
        Long sellerId = jdbc.queryForObject("""
                SELECT p.seller_id FROM opslab.products p
                 WHERE p.seller_id IS NOT NULL
                 GROUP BY p.seller_id ORDER BY p.seller_id LIMIT 1
                """, Long.class);
        jdbc.update("DELETE FROM opslab.seller_tier_assignment WHERE seller_id = ?", sellerId);

        assertThat(adapter.findRoster(LocalDate.now(), 200))
                .filteredOn(row -> row.sellerId().equals(sellerId))
                .as("상품을 가진 셀러는 정본이 없어도 명부에 남아야 한다")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.tier()).isNull();
                    assertThat(row.effectiveFrom()).isNull();
                    assertThat(row.productCount()).isPositive();
                });
    }

    @Test
    @DisplayName("명부는 순매출 내림차순이다 — 등급을 손볼 대상이 위에 온다")
    void rosterIsOrderedByNetSalesDescending() {
        List<RawSellerRow> roster = adapter.findRoster(LocalDate.now(), 200);

        assertThat(roster).extracting(RawSellerRow::netSales12m).isSortedAccordingTo(
                (a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("상한은 명부만 자르고 전체 셀러 수는 줄이지 않는다")
    void limitTruncatesRowsButNotTheTotal() {
        assertThat(adapter.findRoster(LocalDate.now(), 1)).hasSize(1);
        assertThat(adapter.countSellers()).isGreaterThan(1L);
    }
}
