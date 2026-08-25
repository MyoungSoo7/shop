package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.order.application.port.out.LoadOrderQueuePort.StatusWaiting;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 큐 집계 SQL 회귀 테스트 — 실 PostgreSQL.
 *
 * <p><b>왜 단위 테스트로는 안 되는가</b>: 이 기능이 존재하는 이유가 통째로 SQL 한 조각에 들어
 * 있다. "얼마나 오래 밀렸는가"를 {@code orders.created_at} 이 아니라 상태 변경 이력에서 재는 것,
 * 그 이력 중에서도 <b>실제로 상태가 바뀐</b> 행만 보는 것, 이력이 없으면 대신 잰 뒤 그 사실을
 * 세어 올리는 것 — 셋 다 무너져도 서비스 계층은 정상 동작한다. 숫자만 조용히 틀린다.
 *
 * <p>세 가지가 각각 어느 방향으로 틀리는지가 중요하다. 전부 <b>밀린 일이 실제보다 적어 보이는</b>
 * 쪽이다. 오래 기다린 주문이 목록 위로 올라오지 않으면 화면에는 아무 이상이 없다.
 *
 * <h2>고정 데이터</h2>
 * 기준 시각은 {@code 2026-08-26 09:00} 이다.
 * <pre>
 *   O1 CREATED           주문 8/20 09:00, 이력 없음        → 미결제, 대기 144h
 *   O2 PAID              주문 8/01,  CREATED→PAID 8/25 09:00 → 발송대기, 대기 24h
 *   O3 PAID              주문 8/01,  이력 없음              → 발송대기, 대기 600h(추정)
 *   O4 SHIPPING_PENDING  주문 8/01,  PAID→SP 8/05 09:00      → 발송대기, 대기 508h
 *                        + 8/24 09:00 SP→SP (부분 취소 흔적 — 시계를 되돌리면 안 된다)
 *   O5 REFUND_REQUESTED  주문 8/01,  →RR 8/26 08:00          → 환불신청, 대기 1h
 *   O6 IN_TRANSIT        주문 8/01,  →IT 8/24 09:00          → 배송체류, 대기 48h
 *   O7 DELIVERED         종단 — 어느 큐에도 없어야 한다
 * </pre>
 *
 * <p>V17 이 심어 둔 PAID 주문 1000건은 {@code @BeforeEach} 에서 종단 상태로 옮겨 격리한다.
 * 이 질의는 {@code order_items} 가 아니라 {@code orders} 를 직접 세므로, 격리하지 않으면
 * 발송 대기 큐의 숫자가 마이그레이션 실행 시각에 따라 달라진다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class OrderQueueQueryIT {

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

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 9, 0);

    /** 발송 대기 48시간 · 환불 신청 24시간 · 배송 체류 7일 · 미결제 24시간. */
    private static final Map<String, LocalDateTime> DEADLINES = Map.of(
            "CREATED", NOW.minusHours(24),
            "PAID", NOW.minusHours(48),
            "SHIPPING_PENDING", NOW.minusHours(48),
            "REFUND_REQUESTED", NOW.minusHours(24),
            "IN_TRANSIT", NOW.minusHours(24 * 7));

    @PersistenceContext
    EntityManager em;

    @Autowired
    DataSource dataSource;

    private OrderQueueQueryJdbcAdapter adapter;

    private long o1;
    private long o2;
    private long o3;
    private long o4;

    @BeforeEach
    void seed() {
        adapter = new OrderQueueQueryJdbcAdapter(new JdbcTemplate(dataSource));

        // V17 시드 주문 격리 — 종단 상태는 어느 큐도 조회하지 않는다.
        em.createNativeQuery("UPDATE opslab.orders SET status = 'DELIVERED'").executeUpdate();

        o1 = insertOrder("CREATED", "2026-08-20T09:00:00");

        o2 = insertOrder("PAID", "2026-08-01T09:00:00");
        insertHistory(o2, "CREATED", "PAID", "2026-08-25T09:00:00");

        o3 = insertOrder("PAID", "2026-08-01T09:00:00");   // 이력 없음 — 주문 일시로 대신 잰다

        o4 = insertOrder("SHIPPING_PENDING", "2026-08-01T09:00:00");
        insertHistory(o4, "PAID", "SHIPPING_PENDING", "2026-08-05T09:00:00");
        // 부분 취소는 상태를 그대로 둔 채 이력을 남긴다(previous = new).
        insertHistory(o4, "SHIPPING_PENDING", "SHIPPING_PENDING", "2026-08-24T09:00:00");

        long o5 = insertOrder("REFUND_REQUESTED", "2026-08-01T09:00:00");
        insertHistory(o5, "PAID", "REFUND_REQUESTED", "2026-08-26T08:00:00");

        long o6 = insertOrder("IN_TRANSIT", "2026-08-01T09:00:00");
        insertHistory(o6, "SHIPPING_PENDING", "IN_TRANSIT", "2026-08-24T09:00:00");

        long o7 = insertOrder("DELIVERED", "2026-08-01T09:00:00");
        insertHistory(o7, "IN_TRANSIT", "DELIVERED", "2026-08-25T09:00:00");

        em.flush();
    }

    private List<StatusWaiting> query() {
        return adapter.waitingByStatus(DEADLINES);
    }

    private StatusWaiting status(List<StatusWaiting> rows, String status) {
        return rows.stream()
                .filter(r -> r.status().equals(status))
                .findFirst()
                .orElseThrow(() -> new AssertionError("상태 행이 없다: " + status));
    }

    @Test
    @DisplayName("요청한 상태만, 그 상태의 주문 수만큼 나온다")
    void countsRequestedStatusesOnly() {
        List<StatusWaiting> rows = query();

        assertThat(rows).extracting(StatusWaiting::status)
                .containsExactlyInAnyOrder("CREATED", "PAID", "SHIPPING_PENDING",
                        "REFUND_REQUESTED", "IN_TRANSIT");
        assertThat(status(rows, "PAID").count()).isEqualTo(2);          // O2, O3
        assertThat(status(rows, "CREATED").count()).isEqualTo(1);
    }

    /**
     * 이 테스트 하나가 이 기능의 존재 이유다. O2 는 8월 1일에 들어와 8월 25일에 결제됐다.
     * {@code created_at} 으로 재면 25일째 발송이 밀린 주문이고, 실제로는 하루짜리다.
     */
    @Test
    @DisplayName("대기 시간은 주문 일시가 아니라 그 상태가 된 시각부터 잰다")
    void ageComesFromStatusHistory() {
        // PAID 두 건 중 이력이 있는 O2 는 8/25, 이력이 없는 O3 는 주문일 8/01.
        // 가장 오래된 쪽은 O3 이므로, O2 의 이력이 무시되면 이 값만으로는 드러나지 않는다.
        assertThat(status(query(), "IN_TRANSIT").oldestWaitingSince())
                .isEqualTo(LocalDateTime.of(2026, 8, 24, 9, 0));   // 주문일은 8/01 이다
    }

    @Test
    @DisplayName("이력이 없으면 주문 일시로 대신 재고, 그 건수를 따로 센다")
    void fallsBackToOrderDateAndReportsIt() {
        StatusWaiting paid = status(query(), "PAID");

        assertThat(paid.withoutHistoryCount()).isEqualTo(1);            // O3 만
        assertThat(paid.oldestWaitingSince())
                .isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));        // O3 의 주문 일시
    }

    /**
     * 이력이 있는 주문은 이력 없는 주문에 섞여 사라지면 안 된다 — {@code JOIN} 으로 바꾸면
     * 이력 없는 O3 이 통째로 빠져 발송 대기가 한 건 줄어든다.
     */
    @Test
    @DisplayName("이력이 없는 주문도 큐에 남는다")
    void ordersWithoutHistoryStillCounted() {
        assertThat(status(query(), "PAID").count()).isEqualTo(2);
    }

    /**
     * O4 는 8/5 부터 발송 대기였고 8/24 에 부분 취소가 있었다. 부분 취소 이력까지 "이 상태가 된
     * 시각"으로 세면 19일 밀린 주문이 이틀짜리가 된다 — 목록 위로 올라오지 않는 방향이라
     * 화면에는 아무 이상이 없다.
     */
    @Test
    @DisplayName("상태가 바뀌지 않은 이력(부분 취소)은 대기 시계를 되돌리지 않는다")
    void selfTransitionDoesNotResetTheClock() {
        assertThat(status(query(), "SHIPPING_PENDING").oldestWaitingSince())
                .isEqualTo(LocalDateTime.of(2026, 8, 5, 9, 0));
    }

    @Test
    @DisplayName("기한 초과는 상태마다 다른 기준으로 센다")
    void overduePerStatusDeadline() {
        List<StatusWaiting> rows = query();

        // 발송 대기 48시간: O2(24h) 통과 · O3(600h) 초과 · O4(508h) 초과
        assertThat(status(rows, "PAID").overdueCount()).isEqualTo(1);
        assertThat(status(rows, "SHIPPING_PENDING").overdueCount()).isEqualTo(1);
        // 환불 신청 24시간: O5 는 1시간 — 초과 아님
        assertThat(status(rows, "REFUND_REQUESTED").overdueCount()).isZero();
        // 배송 체류 7일: O6 는 48시간 — 초과 아님. 같은 48시간이 발송 대기였다면 초과다.
        assertThat(status(rows, "IN_TRANSIT").overdueCount()).isZero();
        // 미결제 24시간: O1 은 144시간 — 초과
        assertThat(status(rows, "CREATED").overdueCount()).isEqualTo(1);
    }

    /** 기한이 같은 값이면 상태별 기준이 사라진 것이다 — 배송 체류가 이틀 만에 초과로 뜬다. */
    @Test
    @DisplayName("배송 체류에 발송 대기 기준을 주면 초과가 된다")
    void deadlineIsActuallyPerStatus() {
        List<StatusWaiting> rows = adapter.waitingByStatus(Map.of("IN_TRANSIT", NOW.minusHours(24)));

        assertThat(status(rows, "IN_TRANSIT").overdueCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청하지 않은 상태는 세지 않는다 — 종단 상태가 큐에 들어오면 안 된다")
    void unrequestedStatusesAreExcluded() {
        assertThat(query()).extracting(StatusWaiting::status).doesNotContain("DELIVERED");
    }

    @Test
    @DisplayName("주문이 없는 상태는 행 자체가 없다 — 0 으로 만드는 것은 서비스의 일이다")
    void emptyStatusHasNoRow() {
        List<StatusWaiting> rows = adapter.waitingByStatus(
                Map.of("CANCELLATION_APPROVED", NOW.minusHours(24)));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("빈 요청은 질의하지 않는다")
    void emptyRequestReturnsEmpty() {
        assertThat(adapter.waitingByStatus(Map.of())).isEmpty();
        assertThat(adapter.waitingByStatus(null)).isEmpty();
    }

    /**
     * 상태 하나만 물어도 나머지 상태의 주문이 섞이면 안 된다. {@code sla} CTE 조인이 상태
     * 필터 역할을 겸하고 있어, 조인 조건이 무너지면 모든 주문이 한 상태로 뭉친다.
     */
    @Test
    @DisplayName("상태 하나만 물으면 그 상태만 나온다")
    void singleStatusIsIsolated() {
        List<StatusWaiting> rows = adapter.waitingByStatus(Map.of("CREATED", NOW.minusHours(24)));

        assertThat(rows).hasSize(1);
        assertThat(status(rows, "CREATED").count()).isEqualTo(1);
        assertThat(status(rows, "CREATED").oldestWaitingSince())
                .isEqualTo(LocalDateTime.of(2026, 8, 20, 9, 0));
    }

    // ── 시드 헬퍼 ──────────────────────────────────────────────

    private long orderSeq = 940_000L;

    private long insertOrder(String status, String createdAt) {
        long id = ++orderSeq;
        em.createNativeQuery("""
                INSERT INTO opslab.orders(id, user_id, amount, status, shipping_fee, shipped,
                                          created_at, updated_at)
                VALUES (?1, 1, 0, ?2, 0, false, CAST(?3 AS timestamp), CAST(?3 AS timestamp))
                """)
                .setParameter(1, id)
                .setParameter(2, status)
                .setParameter(3, createdAt)
                .executeUpdate();
        return id;
    }

    private void insertHistory(long orderId, String previous, String next, String changedAt) {
        em.createNativeQuery("""
                INSERT INTO opslab.order_status_history(order_id, previous_status, new_status,
                                                        changed_by, reason, changed_at)
                VALUES (?1, ?2, ?3, 'test', 'IT', CAST(?4 AS timestamp))
                """)
                .setParameter(1, orderId)
                .setParameter(2, previous)
                .setParameter(3, next)
                .setParameter(4, changedAt)
                .executeUpdate();
    }
}
