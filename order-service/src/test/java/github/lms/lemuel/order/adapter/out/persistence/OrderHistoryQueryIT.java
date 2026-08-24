package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문내역 조회 쿼리 회귀 테스트 — 실 PostgreSQL.
 *
 * <p>배경: 프론트(MyPage)는 {@code GET /orders/user/{id}} 를 status/from/to 없이 호출한다.
 * 즉 {@link SpringDataOrderJpaRepository#findUserOrders} 가 세 필터 모두 null 로 실행되는데,
 * PostgreSQL 은 {@code $n IS NULL} 형태로만 등장하는 bind 파라미터의 타입을 추론하지 못해
 * {@code SQLState 42P18 "could not determine data type of parameter"} 로 쿼리 전체를 실패시켰다
 * (주문내역이 통째로 500 → "주문내역 안 뜸").
 *
 * <p>H2 는 이 타입 추론 문제를 재현하지 않으므로 반드시 Testcontainers 실 PostgreSQL 에서 검증한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class OrderHistoryQueryIT {

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

    @Autowired
    SpringDataOrderJpaRepository repository;

    @PersistenceContext
    EntityManager em;

    private long userId;

    @BeforeEach
    void seed() {
        userId = System.nanoTime();
        // 같은 사용자 주문 2건: PAID(최근) + CREATED(과거). product_id 는 nullable 이므로 FK 없이 삽입.
        insertOrder(userId, "PAID", "2026-07-12T00:30:00");
        insertOrder(userId, "CREATED", "2026-07-11T12:44:00");
        // 다른 사용자 주문 1건 — 필터가 user 로 제대로 좁혀지는지 확인용.
        insertOrder(userId + 1, "PAID", "2026-07-12T01:00:00");
    }

    private void insertOrder(long uid, String status, String createdAt) {
        em.createNativeQuery("""
                INSERT INTO opslab.orders(user_id, amount, status, shipping_fee, shipped, created_at, updated_at)
                VALUES (?1, ?2, ?3, 0, false, CAST(?4 AS timestamp), CAST(?4 AS timestamp))
                """)
                .setParameter(1, uid)
                .setParameter(2, 2990000)
                .setParameter(3, status)
                .setParameter(4, createdAt)
                .executeUpdate();
        em.flush();
    }

    @Test
    @DisplayName("status/from/to 가 모두 null 이어도 사용자 주문이 정상 조회된다 (42P18 회귀)")
    void findUserOrders_allNullFilters_returnsUserOrders() {
        List<OrderJpaEntity> result = repository.findUserOrders(userId, null, null, null);

        assertThat(result).hasSize(2);
        // ORDER BY created_at DESC → PAID(최근) 가 먼저.
        assertThat(result.get(0).getStatus()).isEqualTo("PAID");
        assertThat(result).allSatisfy(o -> assertThat(o.getUserId()).isEqualTo(userId));
    }

    @Test
    @DisplayName("status 필터가 지정되면 해당 상태만 조회된다")
    void findUserOrders_statusFilter_returnsMatching() {
        List<OrderJpaEntity> result = repository.findUserOrders(userId, "PAID", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("기간(from/to) 필터가 지정되면 그 범위의 주문만 조회된다")
    void findUserOrders_dateRange_filters() {
        LocalDateTime from = LocalDateTime.parse("2026-07-12T00:00:00");
        LocalDateTime to = LocalDateTime.parse("2026-07-12T23:59:59");

        List<OrderJpaEntity> result = repository.findUserOrders(userId, null, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("PAID");
    }
}
