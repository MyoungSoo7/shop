package github.lms.lemuel.sellertier.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드가 <b>등급별 정산 분기를 실제로 태우는지</b> 검증한다.
 *
 * <p>V31 은 시드 상품 20개를 전부 {@code seed_manager@test.com} 한 명에게 붙였다. 그 결과 시드
 * 주문 1,000건이 단일 셀러·단일 등급(NORMAL)으로만 흐르고, 등급별 수수료(NORMAL 3.5% / VIP 2.5% /
 * STRATEGIC 2.0%)·정산주기(T+7/T+3/T+1)·홀드백(30%/10%/0%) 어느 분기도 시드만으로는 한 번도
 * 실행되지 않는다. 정책이 코드에 있어도 데이터가 닿지 않으면 아무도 그 경로를 밟지 않는다.
 *
 * <p>여기서는 세 등급이 모두 존재하고, 상품과 <b>주문</b>이 세 등급에 실제로 걸쳐 있는지 본다.
 * 셀러만 만들고 상품을 안 옮기면 등급은 생겨도 거래가 여전히 한쪽으로 몰린다 — 그래서 주문
 * 분포까지 확인한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class SeedSellerTierSpreadIT {

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

    @Test
    @DisplayName("시드 셀러가 세 등급(NORMAL·VIP·STRATEGIC)을 모두 덮는다")
    void seedSellersCoverEveryTier() throws Exception {
        Map<String, Long> byTier = sellerCountByTier();

        assertThat(byTier.keySet())
                .as("등급별 수수료·주기·홀드백 분기를 태우려면 세 등급이 모두 있어야 한다")
                .containsExactlyInAnyOrder("NORMAL", "VIP", "STRATEGIC");
    }

    @Test
    @DisplayName("시드 상품이 한 셀러에 몰리지 않고 등급별로 흩어져 있다")
    void seedProductsAreSpreadAcrossTiers() throws Exception {
        Map<String, Long> productsByTier = countBy("""
                SELECT u.seller_tier, COUNT(*)
                  FROM opslab.products p
                  JOIN opslab.users u ON u.id = p.seller_id
                 GROUP BY u.seller_tier
                """);

        assertThat(productsByTier.keySet())
                .as("상품이 한 등급에만 있으면 나머지 등급의 정산은 영원히 만들어지지 않는다")
                .containsExactlyInAnyOrder("NORMAL", "VIP", "STRATEGIC");
        assertThat(productsByTier.values()).allSatisfy(
                count -> assertThat(count).as("등급별 상품 수").isGreaterThanOrEqualTo(3L));
    }

    @Test
    @DisplayName("시드 주문이 세 등급 셀러에 모두 걸린다 (분기가 실제로 실행된다)")
    void seedOrdersReachEveryTier() throws Exception {
        Map<String, Long> ordersByTier = countBy("""
                SELECT u.seller_tier, COUNT(*)
                  FROM opslab.orders o
                  JOIN opslab.products p ON p.id = o.product_id
                  JOIN opslab.users u ON u.id = p.seller_id
                 GROUP BY u.seller_tier
                """);

        assertThat(ordersByTier.keySet())
                .as("주문이 닿지 않는 등급은 수수료율이 한 번도 적용되지 않는다")
                .containsExactlyInAnyOrder("NORMAL", "VIP", "STRATEGIC");
        assertThat(ordersByTier.values()).allSatisfy(
                count -> assertThat(count).as("등급별 주문 수").isGreaterThanOrEqualTo(50L));
    }

    @Test
    @DisplayName("새 셀러 계정의 비밀번호가 기존 시드 계정과 동일하다 (로그인 가능)")
    void newSellerAccountsShareTheSeedPasswordHash() throws Exception {
        // 해시를 리터럴로 다시 적는 대신 seed_manager 것을 복사한다. V17 은 주석과 실제 해시가
        // 어긋나 시드 계정 로그인이 항상 401 이었고 V20260706090000 이 뒤늦게 정정했다 —
        // 복사 방식이면 그 정정이 자동으로 따라오지만, 복사 자체가 깨지면 같은 401 이 돌아온다.
        Map<String, Long> mismatched = countBy("""
                SELECT u.email, 1
                  FROM opslab.users u
                 WHERE u.email IN ('seed_seller_vip@test.com', 'seed_seller_strategic@test.com')
                   AND (u.password IS NULL
                        OR u.password <> (SELECT m.password FROM opslab.users m
                                           WHERE m.email = 'seed_manager@test.com'))
                """);

        assertThat(mismatched.keySet())
                .as("비밀번호 해시가 기존 시드와 다르면 이 계정들로는 로그인할 수 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("등급 부여가 seller_tier_assignment 에도 기록된다 (등급 생명주기의 진입점)")
    void tierAssignmentsExistForSeedSellers() throws Exception {
        Map<String, Long> assignments = countBy("""
                SELECT a.tier, COUNT(*)
                  FROM opslab.seller_tier_assignment a
                 GROUP BY a.tier
                """);

        assertThat(assignments.keySet())
                .as("승급·강등 평가는 assignment 행이 있어야 돌아간다")
                .contains("VIP", "STRATEGIC");
    }

    private Map<String, Long> sellerCountByTier() throws Exception {
        return countBy("""
                SELECT u.seller_tier, COUNT(*)
                  FROM opslab.users u
                 WHERE EXISTS (SELECT 1 FROM opslab.products p WHERE p.seller_id = u.id)
                 GROUP BY u.seller_tier
                """);
    }

    private Map<String, Long> countBy(String sql) throws Exception {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }
}
