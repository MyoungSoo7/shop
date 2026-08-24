package github.lms.lemuel.schema;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/**
 * 스키마-엔티티 정합성 통합 테스트.
 *
 * 목적:
 *   - 실제 Postgres 17 컨테이너에 Flyway V1~VN 을 전부 적용한다
 *   - 이어서 Hibernate ddl-auto=validate 가 모든 JPA 엔티티를 검증한다
 *   - 엔티티가 요구하는 컬럼이 마이그레이션에 누락되어 있으면 컨텍스트 로딩에서 실패 → CI 즉시 감지
 *
 * 이 테스트만으로 2026-04 세션에서 드러났던 V22 잘못된 컬럼 참조 / V23 INCLUDE /
 * V27 누락 컬럼 같은 류의 이슈가 자동 감지된다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
// Boot 4 에서 @DataJpaTest 슬라이스는 FlywayAutoConfiguration 을 기본 포함하지 않는다.
// 마이그레이션이 실제로 적용되도록 명시적 import.
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest 가 스캔하는 common 의 outbox 리포지토리 커스텀 프래그먼트가 요구하는 @Component.
// 슬라이스는 일반 @Component 를 로드하지 않으므로 명시 import (없으면 컨텍스트 로드 실패).
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class SchemaIntegrationTest {

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

    /**
     * Spring ApplicationContext 가 성공적으로 부트스트랩되면 통과.
     * 실패 시 원인은 Flyway 실패(잘못된 SQL) 또는 Hibernate schema validation 실패(엔티티-스키마 불일치).
     */
    @Test
    @DisplayName("Flyway 전체 적용 후 Hibernate schema validation 통과")
    void flywayAndHibernateValidationSucceed() {
        // 컨텍스트 로딩 자체가 검증 — 명시적 assertion 불필요
    }
}
