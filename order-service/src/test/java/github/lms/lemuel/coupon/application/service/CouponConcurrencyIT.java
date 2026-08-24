package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.coupon.adapter.out.persistence.CouponPersistenceAdapter;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 선착순 동시성 통합 테스트 — 실 PostgreSQL.
 *
 * <p>{@code CouponService.useCoupon} 이 의존하는 두 DB 레벨 가드를 실제 스레드 경합으로 검증한다:
 * <ul>
 *   <li><b>총 사용 한도</b>: {@code UPDATE coupons SET used_count=used_count+1 WHERE id=? AND used_count<max_uses}
 *       원자적 조건부 UPDATE — 쿠폰 5장에 50명이 동시 요청해도 정확히 5명만 성공</li>
 *   <li><b>1인 1매</b>: {@code coupon_usages(coupon_id, user_id)} UNIQUE 제약 — 같은 사용자가 동시
 *       20회 요청해도 정확히 1건만 성공(나머지는 제약 위반→롤백, used_count 도 되돌아감)</li>
 * </ul>
 *
 * <p>워커 스레드는 {@code useCoupon} 의 2단계(증가→사용기록)를 {@code REQUIRES_NEW} 트랜잭션으로
 * 동일하게 재현한다. 실패(제약 위반) 시 같은 트랜잭션이 통째로 롤백돼 {@code used_count} 증가도 취소된다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CouponPersistenceAdapter.class, OutboxSchema.class})
@ActiveProfiles("test")
class CouponConcurrencyIT {

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
        // 동시 스레드(≤32)+테스트 트랜잭션이 커넥션을 기다리다 타임아웃하지 않도록 풀을 넉넉히 확보.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @Autowired CouponPersistenceAdapter couponAdapter;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("선착순 총 한도: 쿠폰 5장에 50명 동시 사용 — 정확히 5명 성공, 45명 한도초과, used_count=5")
    void fcfs_totalLimit_exactlyMaxUsesSucceed() throws Exception {
        Long couponId = seedCoupon("FCFS-" + System.nanoTime(), /*maxUses*/5);
        final int users = 50;
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            userIds.add(seedUser("fcfs-" + System.nanoTime() + "-" + i + "@test.com"));
        }

        Result r = runConcurrently(users, idx -> attemptUse(couponId, userIds.get(idx)));

        assertThat(r.unexpected).as("예상치 못한 예외").isEmpty();
        assertThat(r.success.get()).as("성공한 사용 수 = 총 한도").isEqualTo(5);
        assertThat(r.limitExceeded.get()).as("한도 초과로 거절된 수").isEqualTo(users - 5);
        assertThat(r.duplicate.get()).as("서로 다른 사용자라 중복 위반 없음").isZero();

        assertThat(usedCount(couponId)).as("최종 used_count 는 정확히 5").isEqualTo(5);
        assertThat(usageRows(couponId)).as("coupon_usages 행도 정확히 5").isEqualTo(5L);
    }

    @Test
    @DisplayName("1인 1매: 같은 사용자가 동시 20회 사용 — 정확히 1건 성공, 19건 UNIQUE 위반, used_count=1")
    void perUserLimit_sameUserConcurrent_onlyOnce() throws Exception {
        Long couponId = seedCoupon("ONCE-" + System.nanoTime(), /*maxUses*/100);
        Long userId = seedUser("once-" + System.nanoTime() + "@test.com");
        final int attempts = 20;

        Result r = runConcurrently(attempts, idx -> attemptUse(couponId, userId));

        assertThat(r.unexpected).as("예상치 못한 예외").isEmpty();
        assertThat(r.success.get()).as("성공한 사용 수").isEqualTo(1);
        assertThat(r.duplicate.get()).as("UNIQUE 제약으로 거절된 수").isEqualTo(attempts - 1);
        assertThat(r.limitExceeded.get()).as("한도(100)는 넉넉해 한도초과 없음").isZero();

        assertThat(usedCount(couponId)).as("실패 롤백으로 used_count 는 정확히 1").isEqualTo(1);
        assertThat(usageRows(couponId)).as("coupon_usages 행도 정확히 1").isEqualTo(1L);
    }

    // --- useCoupon 2단계 재현 (증가 → 사용기록), REQUIRES_NEW 로 독립 커밋 -------------------

    private final TransactionTemplate workerTx() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** CouponService.useCoupon 과 동일한 2단계. 한도 초과면 IllegalStateException, 중복이면 DataIntegrityViolation. */
    private void attemptUse(Long couponId, Long userId) {
        workerTx().executeWithoutResult(s -> {
            if (!couponAdapter.incrementUsageIfAvailable(couponId)) {
                throw new IllegalStateException("쿠폰 사용 한도를 초과했습니다.");
            }
            couponAdapter.recordUsage(couponId, userId, null);
        });
    }

    private Result runConcurrently(int n, java.util.function.IntConsumer task) throws Exception {
        // start 배리어에서 n개 태스크 전원이 풀 스레드를 점유한 채 대기하므로,
        // 풀 크기가 n 미만이면 ready 가 n까지 내려가지 못해 교착한다 — 반드시 n개.
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        Result r = new Result();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.accept(idx);
                    r.success.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    r.duplicate.incrementAndGet();
                } catch (IllegalStateException e) {
                    r.limitExceeded.incrementAndGet();
                } catch (Throwable t) {
                    r.unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        boolean allReady = ready.await(30, TimeUnit.SECONDS);
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(allReady).as("모든 스레드 30초 내 준비 (교착 방지 타임아웃)").isTrue();
        assertThat(finished).as("모든 스레드 30초 내 완료").isTrue();
        return r;
    }

    private static class Result {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger limitExceeded = new AtomicInteger();
        final AtomicInteger duplicate = new AtomicInteger();
        final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    }

    // --- 시드 & 조회 (테스트 롤백 트랜잭션 밖에서 커밋) ---------------------------------------

    private Long seedCoupon(String code, int maxUses) {
        return inNewTx(() -> couponAdapter.save(Coupon.create(
                code, CouponType.FIXED, new BigDecimal("1000"), BigDecimal.ZERO, null,
                maxUses, LocalDateTime.now().plusDays(1))).getId());
    }

    private Long seedUser(String email) {
        return inNewTx(() -> {
            em.createNativeQuery("INSERT INTO opslab.users(email, password) VALUES (?1, ?2)")
                    .setParameter(1, email).setParameter(2, "x").executeUpdate();
            Number id = (Number) em.createNativeQuery("SELECT id FROM opslab.users WHERE email = ?1")
                    .setParameter(1, email).getSingleResult();
            return id.longValue();
        });
    }

    private int usedCount(Long couponId) {
        Number n = (Number) em.createNativeQuery("SELECT used_count FROM opslab.coupons WHERE id = ?1")
                .setParameter(1, couponId).getSingleResult();
        return n.intValue();
    }

    private long usageRows(Long couponId) {
        Number n = (Number) em.createNativeQuery("SELECT count(*) FROM opslab.coupon_usages WHERE coupon_id = ?1")
                .setParameter(1, couponId).getSingleResult();
        return n.longValue();
    }

    private <T> T inNewTx(Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> action.get());
    }
}
