package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnScope;
import github.lms.lemuel.point.domain.exception.PointPolicyOverlapException;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적립률 정책 쓰기 어댑터 — <b>DB 제약이 정본</b>임을 실제 Postgres 로 확인한다.
 *
 * <p>단위 테스트로는 절대 잡히지 않는 것을 본다: {@code ex_pep_no_upverlap} GiST 배제 제약이
 * 실제로 겹침을 막는지, 그리고 그 위반이 500 이 아니라 {@link PointPolicyOverlapException}(409)
 * 으로 번역되는지. 이 번역이 없으면 운영자에게는 "서버 오류"로 보여, 정작 해야 할 일
 * (현재 정책을 먼저 종료)을 알 수 없다.
 *
 * <p>종료가 {@code effective_to} 를 실제로 옮겨 <b>자리를 비우는지</b>도 함께 본다 —
 * {@code closed_at} 만 찍고 끝냈다면 제약은 여전히 겹침으로 판정해 다음 정책을 넣을 수 없다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxSchema.class, PointEarnPolicyPersistenceAdapter.class})
@ActiveProfiles("test")
class PointEarnPolicyPersistenceAdapterIT {

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

    @Autowired PointEarnPolicyPersistenceAdapter adapter;

    private static PointEarnPolicy policy(String rate, LocalDate from, LocalDate to) {
        return PointEarnPolicy.of(PointEarnScope.GLOBAL, "-", new BigDecimal(rate), 365,
                from, to, "테스트 요율", "admin:1");
    }

    @Test
    @DisplayName("정책을 저장하고 id 로 다시 읽는다")
    void savesAndReads() {
        PointEarnPolicy saved = adapter.save(
                policy("0.01000", LocalDate.of(2030, 1, 1), null));

        assertThat(saved.getId()).isNotNull();
        Optional<PointEarnPolicy> found = adapter.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getEarnRate()).isEqualByComparingTo("0.01000");
    }

    @Test
    @DisplayName("같은 범위에 기간이 겹치면 409 로 번역한다 — 500 이면 운영자가 원인을 못 본다")
    void rejectsOverlapAsConflict() {
        adapter.save(policy("0.01000", LocalDate.of(2031, 1, 1), null));

        assertThatThrownBy(() -> adapter.save(policy("0.02000", LocalDate.of(2031, 6, 1), null)))
                .isInstanceOf(PointPolicyOverlapException.class)
                .hasMessageContaining("종료일을 먼저 지정");
    }

    @Test
    @DisplayName("경계가 맞닿는 구간은 겹침이 아니다 — daterange 가 반열림이라 [from, to) 다")
    void adjacentRangesAreAllowed() {
        adapter.save(policy("0.01000", LocalDate.of(2032, 1, 1), LocalDate.of(2032, 7, 1)));

        assertThatCode(() -> adapter.save(
                policy("0.02000", LocalDate.of(2032, 7, 1), null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("종료가 effective_to 를 옮겨 자리를 비운다 — 그래야 다음 정책이 들어간다")
    void closeFreesTheRangeForTheNextPolicy() {
        PointEarnPolicy current = adapter.save(
                policy("0.01000", LocalDate.of(2033, 1, 1), null));

        Optional<PointEarnPolicy> closed = adapter.close(current.getId(), LocalDate.of(2033, 9, 1));

        assertThat(closed).isPresent();
        assertThat(closed.orElseThrow().getEffectiveTo()).isEqualTo(LocalDate.of(2033, 9, 1));
        // 자리가 비었으므로 후속 정책이 들어간다 — closed_at 만 찍었다면 여기서 겹침으로 거절된다.
        assertThatCode(() -> adapter.save(
                policy("0.02000", LocalDate.of(2033, 9, 1), null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("없는 정책을 종료하면 비어 있는 결과를 준다")
    void closeMissingPolicy() {
        assertThat(adapter.close(999_999L, LocalDate.of(2034, 1, 1))).isEmpty();
    }
}
