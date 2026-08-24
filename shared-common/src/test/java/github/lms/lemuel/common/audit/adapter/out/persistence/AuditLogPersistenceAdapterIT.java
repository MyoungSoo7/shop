package github.lms.lemuel.common.audit.adapter.out.persistence;

import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.audit.domain.AuditLog;
import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * audit_logs 테이블이 Flyway V34 로 생성되고, AuditLogPersistenceAdapter 가
 * 도메인 ↔ 엔티티 매핑을 올바르게 수행하는지 Testcontainers 로 검증.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AuditLogPersistenceAdapter.class, OutboxSchema.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AuditLogPersistenceAdapterIT {

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
    AuditLogPersistenceAdapter adapter;

    @Test
    @DisplayName("audit log 저장 및 필드 보존")
    void savesAndPreservesFields() {
        AuditLog toSave = AuditLog.of(
                AuditAction.SETTLEMENT_CONFIRMED,
                "Settlement", "100",
                "{\"amount\":50000,\"status\":\"DONE\"}",
                42L, "admin@test.com",
                "10.0.0.1", "curl/8.0");

        AuditLog saved = adapter.save(toSave);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAction()).isEqualTo(AuditAction.SETTLEMENT_CONFIRMED);
        assertThat(saved.getActorEmail()).isEqualTo("admin@test.com");
        assertThat(saved.getResourceType()).isEqualTo("Settlement");
        assertThat(saved.getResourceId()).isEqualTo("100");
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("SYSTEM actor (null) 도 저장 가능 — 스케줄러/배치 경로")
    void savesWithSystemActor() {
        AuditLog toSave = AuditLog.of(
                AuditAction.REFUND_COMPLETED,
                "Refund", "7", null,
                null, null, null, null);

        AuditLog saved = adapter.save(toSave);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getActorEmail()).isNull();
    }
}
