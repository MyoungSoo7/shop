package github.lms.lemuel.point.application.service;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.point.adapter.out.persistence.PointPersistenceAdapter;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointHold;
import github.lms.lemuel.point.domain.PointHoldStatus;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입금 vs 만료 경합 — Phase 2 의 완료 판정(docs/plan/point-ledger.md §12).
 *
 * <p>입금 통보와 미입금 만료 배치는 <b>독립적으로 도착</b>한다. 같은 선점에 둘이 동시에 닿으면
 * 둘 중 하나만 이겨야 한다:
 *
 * <ul>
 *   <li>둘 다 성공하면 — 포인트를 쓰고(확정) 동시에 돌려준(해제) 것이 되어 <b>없는 잔고가 생긴다</b>.
 *   <li>둘 다 실패하면 — 잔고가 영영 잠긴 채 남는다.
 * </ul>
 *
 * <p>단위 테스트로는 이 사고를 볼 수 없다. 목은 언제나 순서대로 답하기 때문에, 실제로 두 트랜잭션이
 * 같은 행을 두고 겨루는 상황을 재현해야 한다 — 그래서 실 PostgreSQL 이다.
 *
 * <p>방어선은 둘이다: 계정 행의 <b>비관적 락</b>이 두 트랜잭션을 줄 세우고, 그 뒤 선점의
 * <b>종단 전이 가드</b>가 늦게 들어온 쪽을 거절한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PointPersistenceAdapter.class, OutboxSchema.class})
@ActiveProfiles("test")
class PointHoldConcurrencyIT {

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @Autowired PointPersistenceAdapter adapter;
    @Autowired PlatformTransactionManager txManager;

    private static final String REF_TYPE = "PAYMENT_TENDER";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-21T10:00:00+09:00");

    /** 이벤트는 이 테스트의 관심사가 아니다 — 경합 판정만 본다. */
    private static final github.lms.lemuel.point.application.port.out.PublishPointEventPort NO_EVENTS =
            new github.lms.lemuel.point.application.port.out.PublishPointEventPort() {
                @Override public void pointCharged(PointAccount a, PointLot l, String r) { }
                @Override public void pointGranted(PointAccount a, PointLot l) { }
                @Override public void pointUsed(PointAccount a, PointEntry e) { }
                @Override public void pointRestored(PointAccount a, PointEntry e) { }
                @Override public void pointRevoked(PointAccount a, PointEntry e) { }
                @Override public void pointExpired(PointAccount a, PointLot l, BigDecimal f) { }
            };

    private HoldPointService service() {
        return new HoldPointService(adapter, adapter,
                new PointSpendRecorder(adapter, adapter, adapter, NO_EVENTS));
    }

    /** 각 워커는 자기 트랜잭션에서 돈다 — 그래야 두 트랜잭션이 실제로 같은 행을 두고 겨룬다. */
    private TransactionTemplate newTx() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private long seedHeldAccount(long userId, String refId) {
        return newTx().execute(status -> {
            PointAccount account = adapter.openIfAbsent(userId);
            account.grant(new BigDecimal("10000"));
            adapter.save(account);
            adapter.save(PointLot.issue(account.getId(), PointLotOrigin.ORDER_EARN,
                    new BigDecimal("10000"), NOW, null, "SEED", refId));
            account.hold(new BigDecimal("3000"));
            adapter.save(account);
            adapter.save(PointHold.place(account.getId(), new BigDecimal("3000"),
                    REF_TYPE, refId, NOW));
            return account.getId();
        });
    }

    @Test
    @DisplayName("확정과 해제가 동시에 닿으면 정확히 하나만 이긴다 — 잠금은 남지 않는다")
    void captureAndReleaseRaceHasExactlyOneWinner() throws Exception {
        String refId = "race-" + System.nanoTime();
        long accountId = seedHeldAccount(90100L + (System.nanoTime() % 1000), refId);

        final int attempts = 16;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<String> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                final boolean isDeposit = i % 2 == 0;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        newTx().executeWithoutResult(s -> {
                            if (isDeposit) {
                                service().capture(REF_TYPE, refId, "user:1");
                                captured.incrementAndGet();
                            } else {
                                service().release(REF_TYPE, refId, true);
                                released.incrementAndGet();
                            }
                        });
                    } catch (github.lms.lemuel.point.domain.exception.InvalidPointStateException
                             | github.lms.lemuel.point.domain.exception.PointInvariantViolationException e) {
                        rejected.incrementAndGet();   // 늦게 들어온 쪽 — 기대한 거절
                    } catch (Exception e) {
                        // 락 경합으로 인한 재시도성 예외까지 성공으로 세지 않는다.
                        unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 멱등 단축 반환도 예외 없이 통과하므로, 최종 상태로 승자를 판정한다.
        PointHold finalHold = newTx().execute(s -> adapter.findByReference(REF_TYPE, refId).orElseThrow());
        PointAccount finalAccount = newTx().execute(s -> adapter.loadByIdForUpdate(accountId).orElseThrow());

        assertThat(unexpected).as("예상치 못한 예외").isEmpty();
        assertThat(finalHold.getStatus()).as("선점은 종단 상태로 확정된다")
                .isIn(PointHoldStatus.CAPTURED, PointHoldStatus.EXPIRED);
        assertThat(finalAccount.getLocked()).as("잠금은 남지 않는다").isEqualByComparingTo("0");

        if (finalHold.getStatus() == PointHoldStatus.CAPTURED) {
            // 입금이 이겼다 — 3,000 을 실제로 썼다.
            assertThat(finalAccount.getTotal()).isEqualByComparingTo("7000");
            assertThat(finalAccount.getAvailable()).isEqualByComparingTo("7000");
            assertThat(released.get()).as("해제가 성공한 적은 없어야 한다").isZero();
        } else {
            // 만료가 이겼다 — 3,000 이 가용으로 돌아왔다.
            assertThat(finalAccount.getTotal()).isEqualByComparingTo("10000");
            assertThat(finalAccount.getAvailable()).isEqualByComparingTo("10000");
            assertThat(captured.get()).as("확정이 성공한 적은 없어야 한다").isZero();
        }
    }

    /**
     * 배치 재실행은 정상이다. 같은 해제가 여러 번 도착해도 잔고가 늘어나면 안 된다 —
     * 늘어나면 재실행할 때마다 없는 포인트가 생긴다.
     */
    @Test
    @DisplayName("같은 해제가 동시에 여러 번 와도 잔고는 한 번만 돌아온다")
    void concurrentReleasesRestoreOnce() throws Exception {
        String refId = "rel-" + System.nanoTime();
        long accountId = seedHeldAccount(90200L + (System.nanoTime() % 1000), refId);

        final int attempts = 12;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<String> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        newTx().executeWithoutResult(s -> service().release(REF_TYPE, refId, true));
                    } catch (Exception e) {
                        unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        PointAccount finalAccount = newTx().execute(s -> adapter.loadByIdForUpdate(accountId).orElseThrow());

        assertThat(unexpected).as("해제 재실행은 예외 없이 멱등이어야 한다").isEmpty();
        assertThat(finalAccount.getAvailable()).isEqualByComparingTo("10000");
        assertThat(finalAccount.getLocked()).isEqualByComparingTo("0");
        assertThat(finalAccount.getTotal()).isEqualByComparingTo("10000");
    }
}
