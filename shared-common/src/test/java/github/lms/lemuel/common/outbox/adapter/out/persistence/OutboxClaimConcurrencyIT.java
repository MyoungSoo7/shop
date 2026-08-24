package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox claim(리스) 기반 멀티워커 발행의 동시성/정확성 통합 테스트.
 *
 * <p>검증 대상은 {@link OutboxEventPersistenceAdapter} 가 구현한 claim 네이티브 SQL
 * ({@code SELECT ... FOR UPDATE SKIP LOCKED} + stamp/clear claim) 과
 * V20260611110000 마이그레이션의 claim 컬럼/인덱스다.
 *
 * <p><b>왜 @DataJpaTest 기본 롤백 트랜잭션을 끄는가</b>:
 * SKIP LOCKED 의 disjoint 분배와 PostgreSQL {@code now()} 기반 리스 만료는
 * <em>커밋된 데이터 + 워커마다 독립 트랜잭션</em> 이 있어야 관측된다. 단일 테스트 트랜잭션에
 * 묶이면 (1) 다른 스레드가 미커밋 행을 못 보고 (2) {@code now()} 가 트랜잭션 시작 시각으로
 * 고정돼 리스가 진행하지 않는다. 따라서 {@code NOT_SUPPORTED} 로 테스트 트랜잭션을 끄고,
 * 각 claim 호출이 어댑터의 {@code @Transactional} 로 자체 트랜잭션을 열게 한다.
 * (커밋되므로 {@link #clean()} 에서 매 테스트 전 비운다.)
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxEventPersistenceAdapter.class, OutboxSchema.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxClaimConcurrencyIT {

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
        // 동시 워커 + 메인 스레드가 각자 커넥션을 잡으므로 풀을 넉넉히
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        // claim 네이티브 쿼리가 opslab.outbox_events 를 하드코딩 → 스키마를 opslab 로 정렬
        // (이 클래스 컨텍스트에만 적용 — 다른 shared-common 테스트엔 영향 없음)
        registry.add("spring.flyway.schemas", () -> "opslab");
        registry.add("spring.flyway.default-schema", () -> "opslab");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "opslab");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // N4 봉투의 producer 는 이 값에서 채워진다
        registry.add("spring.application.name", () -> "lemuel-test");
    }

    @Autowired OutboxEventPersistenceAdapter adapter;
    @Autowired SpringDataOutboxEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll(); // 직전 테스트의 커밋된 시드 제거
    }

    private void seedPending(int n) {
        List<OutboxEvent> events = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            events.add(OutboxEvent.pending("Settlement", "agg-" + i, "SettlementCreated", "{\"i\":" + i + "}"));
        }
        adapter.saveAll(events); // @Transactional → 커밋
    }

    @Test
    @DisplayName("N4: 봉투(occurred_at UTC·event_version·producer)가 실제 DDL 을 왕복해도 보존된다")
    void envelopeRoundTripsThroughPostgres() {
        OutboxEvent saved = adapter.save(
                OutboxEvent.pending("Settlement", "agg-n4", "SettlementCreated", "{}"));

        OutboxEvent loaded = adapter.findByEventId(saved.getEventId()).orElseThrow();

        // producer 는 도메인이 아니라 어댑터가 spring.application.name 으로 채운다
        assertThat(loaded.getProducer()).isEqualTo("lemuel-test");
        assertThat(loaded.getEventVersion()).isEqualTo(OutboxEvent.DEFAULT_EVENT_VERSION);
        // timestamptz 왕복 — 저장 시각과 같은 순간이어야 한다(오프셋 표현은 달라질 수 있음)
        assertThat(loaded.getOccurredAt().toInstant())
                .isEqualTo(saved.getOccurredAt().toInstant());
    }

    @Test
    @DisplayName("동시 6 워커가 PENDING 60건을 claim — 겹침 없이(SKIP LOCKED) 정확히 한 번씩 분배")
    void concurrentClaim_partitionsRowsDisjointly() throws Exception {
        seedPending(60);

        final int workers = 6;
        final int limitPerWorker = 15; // 6 * 15 = 90 ≥ 60 → 모든 행이 claim 됨

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        List<List<Long>> claimedPerWorker = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int w = 0; w < workers; w++) {
            final String worker = "worker-" + w;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // 모든 워커 동시 시작
                    List<OutboxEvent> claimed = adapter.claimPending(limitPerWorker, Duration.ofSeconds(120), worker);
                    claimedPerWorker.add(claimed.stream().map(OutboxEvent::getId).toList());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        boolean allReady = ready.await(30, TimeUnit.SECONDS);
        start.countDown();
        assertThat(allReady).as("모든 워커 30초 내 준비 (교착 방지 타임아웃)").isTrue();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 워커 30초 내 완료").isTrue();
        pool.shutdown();

        assertThat(errors).as("claim 중 예외 없음").isEmpty();

        List<Long> allClaimed = claimedPerWorker.stream().flatMap(List::stream).toList();
        assertThat(allClaimed).as("워커 간 동일 행 중복 claim 없음 — FOR UPDATE SKIP LOCKED").doesNotHaveDuplicates();
        assertThat(allClaimed).as("PENDING 60건 모두 정확히 한 번씩 claim").hasSize(60);
    }

    @Test
    @DisplayName("리스 유효 구간에는 이미 claim 된 행을 다른 워커가 다시 가져가지 못한다")
    void claimedRows_excludedWithinLease() {
        seedPending(10);

        List<OutboxEvent> first = adapter.claimPending(10, Duration.ofSeconds(120), "w1");
        assertThat(first).hasSize(10);

        List<OutboxEvent> second = adapter.claimPending(10, Duration.ofSeconds(120), "w2");
        assertThat(second).as("리스가 살아있어 재클레임 불가").isEmpty();
    }

    @Test
    @DisplayName("리스가 만료되면 다른 워커가 회수한다 (워커 사망 후 자동 복구)")
    void expiredLease_allowsReclaim() throws Exception {
        seedPending(5);

        List<OutboxEvent> first = adapter.claimPending(5, Duration.ofSeconds(1), "dead-worker");
        assertThat(first).hasSize(5);

        Thread.sleep(1200); // 1초 리스 만료 대기

        List<OutboxEvent> reclaimed = adapter.claimPending(5, Duration.ofSeconds(1), "recovery-worker");
        assertThat(reclaimed).as("리스 만료 → 회수됨").hasSize(5);
        assertThat(reclaimed).extracting(OutboxEvent::getId)
                .as("죽은 워커가 잡았던 바로 그 행들")
                .containsExactlyInAnyOrderElementsOf(first.stream().map(OutboxEvent::getId).toList());
    }

    @Test
    @DisplayName("PENDING 행만 claim 된다 — PUBLISHED/FAILED 는 제외")
    void onlyPendingRows_areClaimed() {
        adapter.save(OutboxEvent.pending("A", "pending-1", "E", "{}"));

        OutboxEvent published = OutboxEvent.pending("A", "published-1", "E", "{}");
        published.markPublished();
        adapter.save(published);

        OutboxEvent failed = OutboxEvent.pending("A", "failed-1", "E", "{}");
        while (!failed.isFailed()) {
            failed.markFailed("forced failure");
        }
        adapter.save(failed);

        List<OutboxEvent> claimed = adapter.claimPending(10, Duration.ofSeconds(60), "w");

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(claimed.get(0).getAggregateId()).isEqualTo("pending-1");
    }

    @Test
    @DisplayName("releaseClaim 후에는 즉시 재클레임 가능 (재시도가 필요한 행을 다음 주기로 넘김)")
    void releaseClaim_makesRowsImmediatelyReclaimable() {
        seedPending(3);

        List<OutboxEvent> claimed = adapter.claimPending(3, Duration.ofSeconds(120), "w1");
        assertThat(claimed).hasSize(3);
        assertThat(adapter.claimPending(3, Duration.ofSeconds(120), "w2"))
                .as("리스 유효 → 재클레임 불가").isEmpty();

        adapter.releaseClaim(claimed.stream().map(OutboxEvent::getId).toList());

        assertThat(adapter.claimPending(3, Duration.ofSeconds(120), "w3"))
                .as("리스 해제 → 즉시 재클레임").hasSize(3);
    }
}
