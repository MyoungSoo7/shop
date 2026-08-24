package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointHold;
import github.lms.lemuel.point.domain.PointHoldStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 선점 영속 — <b>스키마가 정본</b>임을 실제 Postgres 로 확인한다.
 *
 * <p>단위 테스트로는 잡히지 않는 것을 본다: 마이그레이션의 컬럼·타입이 엔티티와 맞는지
 * (Hibernate {@code validate} 가 컨텍스트 기동에서 걸러 준다), 같은 근거로 선점이 두 번
 * 들어가지 못하는지, 그리고 상태와 해소 시각이 따로 놀 수 없는지.
 *
 * <p>마지막 항목이 특히 DB 몫이다 — 도메인은 항상 둘을 함께 움직이지만, 수기 SQL·부분 저장 같은
 * 경로가 생기면 "풀렸는데 언제인지 모르는" 행이 남고 그 순간 경합 재구성이 불가능해진다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxSchema.class, PointPersistenceAdapter.class})
@ActiveProfiles("test")
class PointHoldPersistenceIT {

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

    @Autowired PointPersistenceAdapter adapter;
    @Autowired PointHoldRepository holds;

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-21T10:00:00+09:00");

    private Long openAccount(long userId, String grant) {
        PointAccount account = adapter.openIfAbsent(userId);
        account.grant(new BigDecimal(grant));
        return adapter.save(account).getId();
    }

    @Test
    @DisplayName("선점을 저장하고 근거로 되찾는다 — 금액·상태·시각이 왕복에서 보존된다")
    void saveAndFindByReference() {
        Long accountId = openAccount(9001L, "10000");

        adapter.save(PointHold.place(accountId, new BigDecimal("3000"),
                "PAYMENT_TENDER", "tender-1", NOW));

        PointHold found = adapter.findByReference("PAYMENT_TENDER", "tender-1").orElseThrow();
        assertThat(found.getAccountId()).isEqualTo(accountId);
        assertThat(found.getAmount()).isEqualByComparingTo("3000");
        assertThat(found.getStatus()).isEqualTo(PointHoldStatus.ACTIVE);
        assertThat(found.getResolvedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("해소는 상태와 시각을 함께 저장한다 — 생성 시각은 덮이지 않는다")
    void resolvePersistsStatusAndTime() {
        Long accountId = openAccount(9002L, "10000");
        PointHold hold = adapter.save(PointHold.place(accountId, new BigDecimal("2000"),
                "PAYMENT_TENDER", "tender-2", NOW));
        OffsetDateTime created = adapter.findByReference("PAYMENT_TENDER", "tender-2")
                .orElseThrow().getCreatedAt();

        hold.expire(NOW.plusHours(50));
        adapter.save(hold);
        holds.flush();

        PointHold reloaded = adapter.findByReference("PAYMENT_TENDER", "tender-2").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PointHoldStatus.EXPIRED);
        assertThat(reloaded.getResolvedAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
    }

    /** 재시도가 선점을 두 벌 만들면 같은 잔고를 두 번 잠근다 — DB 가 최후 방어선이다. */
    @Test
    @DisplayName("같은 근거로 두 번 선점할 수 없다 (uq_point_holds_reference)")
    void referenceIsUnique() {
        Long accountId = openAccount(9003L, "10000");
        adapter.save(PointHold.place(accountId, new BigDecimal("1000"),
                "PAYMENT_TENDER", "tender-3", NOW));
        holds.flush();

        // IDENTITY 채번이라 save 가 곧바로 INSERT 를 보낸다 — 예외는 flush 가 아니라 여기서 난다.
        assertThatThrownBy(() -> adapter.save(PointHold.place(accountId, new BigDecimal("1000"),
                "PAYMENT_TENDER", "tender-3", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("활성 선점 합계 — 해소된 건은 세지 않고, 하나도 없으면 0 이다(null 아님)")
    void activeAmountSumsOnlyActive() {
        Long accountId = openAccount(9004L, "10000");
        assertThat(adapter.activeAmount(accountId)).isEqualByComparingTo("0");

        adapter.save(PointHold.place(accountId, new BigDecimal("1500"),
                "PAYMENT_TENDER", "tender-4a", NOW));
        PointHold second = adapter.save(PointHold.place(accountId, new BigDecimal("2500"),
                "PAYMENT_TENDER", "tender-4b", NOW));
        holds.flush();
        assertThat(adapter.activeAmount(accountId)).isEqualByComparingTo("4000");

        second.release(NOW.plusMinutes(5));
        adapter.save(second);
        holds.flush();

        assertThat(adapter.activeAmount(accountId)).isEqualByComparingTo("1500");
    }
}
