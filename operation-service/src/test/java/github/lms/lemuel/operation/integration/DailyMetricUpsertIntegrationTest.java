package github.lms.lemuel.operation.integration;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.dashboard.application.port.in.ViewTodayOverviewUseCase;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadDailyMetricPort;
import github.lms.lemuel.operation.dashboard.application.port.out.LoadOperationHealthPort;
import github.lms.lemuel.operation.dashboard.application.port.out.UpsertDailyMetricPort;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import github.lms.lemuel.operation.dashboard.domain.TodayOverview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ops_daily_metric} 누적과 "오늘 한눈에" 조립 — 실 PostgreSQL(Testcontainers) + 실 Flyway.
 *
 * <p>여기서만 확인할 수 있는 것들이다. 목으로는 {@code ON CONFLICT DO UPDATE} 가 정말 더하는지,
 * 마이그레이션이 실제로 도는지, 상태 문자열이 CHECK 제약과 맞는지 알 수 없다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class DailyMetricUpsertIntegrationTest {

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
            .withDatabaseName("operation_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    UpsertDailyMetricPort upsertPort;
    @Autowired
    LoadDailyMetricPort loadPort;
    @Autowired
    LoadOperationHealthPort healthPort;
    @Autowired
    ViewTodayOverviewUseCase overviewUseCase;
    @Autowired
    JdbcTemplate jdbc;

    private static final LocalDate DAY = LocalDate.of(2026, 8, 25);

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM opslab.ops_daily_metric");
        jdbc.update("DELETE FROM opslab.notification_dispatches");
        jdbc.update("DELETE FROM opslab.incidents");
    }

    private DailyMetric load(DashboardMetric metric) {
        return loadPort.findByDate(DAY).stream()
                .filter(row -> row.metric() == metric)
                .findFirst().orElseThrow();
    }

    /**
     * 읽어서 더한 뒤 쓰는 구현이면 동시 도착 시 한쪽이 덮여 사라진다. 결과는 <b>조금 작은
     * 매출</b>이라 아무도 이상하게 여기지 않는다 — 그래서 DB 안에서 더하는지 확인한다.
     */
    @Test
    @DisplayName("같은 날·같은 지표는 한 행에 건수와 금액이 누적된다")
    void accumulatesIntoOneRow() {
        upsertPort.accumulate(DAY, DashboardMetric.PAYMENT_CAPTURED, new BigDecimal("45000"));
        upsertPort.accumulate(DAY, DashboardMetric.PAYMENT_CAPTURED, new BigDecimal("30000.50"));
        upsertPort.accumulate(DAY, DashboardMetric.PAYMENT_CAPTURED, new BigDecimal("1000"));

        DailyMetric row = load(DashboardMetric.PAYMENT_CAPTURED);
        assertThat(row.eventCount()).isEqualTo(3);
        assertThat(row.amountSum()).isEqualByComparingTo("76000.50");
        assertThat(row.amountComplete()).isTrue();
    }

    /**
     * 모르는 금액을 0 으로 합산하면 합계가 "맞는 것처럼 보이는 틀린 값"이 된다.
     * 건수는 세되, 금액을 몇 건이나 못 읽었는지를 따로 남겨 화면이 말할 수 있게 한다.
     */
    @Test
    @DisplayName("금액 미상은 합계를 건드리지 않고 미상 건수만 올린다")
    void unknownAmountIsCountedSeparately() {
        upsertPort.accumulate(DAY, DashboardMetric.PAYMENT_REFUNDED, new BigDecimal("5000"));
        upsertPort.accumulate(DAY, DashboardMetric.PAYMENT_REFUNDED, null);

        DailyMetric row = load(DashboardMetric.PAYMENT_REFUNDED);
        assertThat(row.eventCount()).isEqualTo(2);
        assertThat(row.amountSum()).isEqualByComparingTo("5000");
        assertThat(row.amountUnknownCount()).isEqualTo(1);
        assertThat(row.amountComplete()).isFalse();
    }

    @Test
    @DisplayName("날짜와 지표가 다르면 다른 행이다")
    void distinctKeysAreSeparateRows() {
        upsertPort.accumulate(DAY, DashboardMetric.ORDER_CREATED, new BigDecimal("1000"));
        upsertPort.accumulate(DAY, DashboardMetric.USER_REGISTERED, null);
        upsertPort.accumulate(DAY.minusDays(1), DashboardMetric.ORDER_CREATED, new BigDecimal("2000"));

        assertThat(loadPort.findByDate(DAY)).hasSize(2);
        assertThat(loadPort.findByDate(DAY.minusDays(1))).hasSize(1);
    }

    /**
     * 상태 문자열은 마이그레이션의 CHECK 제약이 정본이다. 어댑터가 오타를 내면 세지 못하는데,
     * 그 실패는 "0건"으로 보여서 <b>좋은 소식으로 위장</b>된다.
     */
    @Test
    @DisplayName("미해결 인시던트는 OPEN·ACKNOWLEDGED 만 센다")
    void countsOnlyActiveIncidents() {
        insertIncident("OPEN");
        insertIncident("ACKNOWLEDGED");
        insertIncident("RESOLVED");
        insertIncident("FALSE_POSITIVE");

        assertThat(healthPort.countOpenIncidents()).isEqualTo(2);
    }

    /**
     * NO_CHANNEL 은 활성 채널이 0개라는 배포 설정 문제지 발송 실패가 아니다. 같이 세면 채널을
     * 안 붙인 환경에서 실패 카드가 늘 빨간불이라, 진짜 실패를 아무도 안 본다.
     */
    @Test
    @DisplayName("실패 알림은 FAILED·PARTIAL 만 세고 NO_CHANNEL 은 제외한다")
    void countsOnlyRealDispatchFailures() {
        Instant since = Instant.now().minusSeconds(3600);
        insertDispatch("FAILED");
        insertDispatch("PARTIAL");
        insertDispatch("DELIVERED");
        insertDispatch("NO_CHANNEL");
        insertDispatch("PENDING");

        assertThat(healthPort.countFailedDispatchesSince(since)).isEqualTo(2);
    }

    @Test
    @DisplayName("기간 밖의 실패는 오늘 숫자에 들어가지 않는다")
    void olderFailuresAreOutOfWindow() {
        insertDispatch("FAILED");

        assertThat(healthPort.countFailedDispatchesSince(Instant.now().plusSeconds(60))).isZero();
    }

    @Test
    @DisplayName("화면 한 장에 네 지표가 모두 있고, 없는 지표는 0 으로 채워진다")
    void overviewAlwaysCarriesEveryMetric() {
        upsertPort.accumulate(DAY, DashboardMetric.ORDER_CREATED, new BigDecimal("45000"));

        TodayOverview overview = overviewUseCase.onDate(DAY);

        assertThat(overview.metrics()).hasSize(DashboardMetric.values().length);
        assertThat(overview.metrics().stream().map(DailyMetric::metric).toList())
                .containsExactlyElementsOf(List.of(DashboardMetric.values()));
        assertThat(overview.asOf()).isNotNull();
    }

    /** correlation_key 는 상태마다 다르게 준다 — 활성 인시던트는 (source, correlation_key) 가 유일해야 한다. */
    private void insertIncident(String status) {
        jdbc.update("""
                INSERT INTO opslab.incidents
                    (correlation_key, source, category, severity, status, title,
                     first_seen_at, last_seen_at)
                VALUES (?, 'MANUAL', 'UNKNOWN', 'WARNING', ?, '테스트', NOW(), NOW())
                """, "test-" + status, status);
    }

    private void insertDispatch(String status) {
        jdbc.update("""
                INSERT INTO opslab.notification_dispatches
                    (event_id, type, recipient, subject, status, created_at)
                VALUES (?, 'TEST', 'ops@lemuel.io', '테스트', ?, ?)
                """, UUID.randomUUID().toString(), status, Timestamp.from(Instant.now()));
    }
}
