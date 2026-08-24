package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.order.adapter.out.lock.InMemoryDistributedLockAdapter;
import github.lms.lemuel.order.adapter.out.persistence.OrderIdempotencyPersistenceAdapter;
import github.lms.lemuel.order.adapter.out.persistence.OrderPersistenceAdapter;
import github.lms.lemuel.order.adapter.out.persistence.OrderPersistenceMapperImpl;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceMapperImpl;
import github.lms.lemuel.product.adapter.out.persistence.ProductVariantPersistenceAdapter;
import github.lms.lemuel.product.application.service.DecreaseProductStockService;
import github.lms.lemuel.product.application.service.DecreaseVariantStockService;
import github.lms.lemuel.product.domain.Product;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중복 주문 제출 방지 동시성 통합 테스트 — 실 PostgreSQL.
 *
 * <p>동일 Idempotency-Key 로 동시 다발 요청이 와도 주문이 정확히 1건만 생성되는지 검증한다.
 * <ul>
 *   <li><b>락 켜짐</b>(InMemory 뮤텍스): 동시 요청이 직렬화돼 전부 같은 주문을 멱등 반환.</li>
 *   <li><b>락 우회</b>(no-op): 락이 없어도 {@code order_idempotency} PK(UNIQUE) 백스톱이 두 번째
 *       주문 트랜잭션을 롤백 → 최종 1건. (분산 락이 비활성/만료된 멀티 인스턴스 상황 모사)</li>
 * </ul>
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductPersistenceAdapter.class,
        github.lms.lemuel.category.adapter.out.persistence.PrimaryCategoryLookupAdapter.class, ProductPersistenceMapperImpl.class,
        ProductVariantPersistenceAdapter.class,
        OrderPersistenceAdapter.class, OrderPersistenceMapperImpl.class,
        OrderIdempotencyPersistenceAdapter.class, OutboxSchema.class})
@ActiveProfiles("test")
class IdempotentOrderConcurrencyIT {

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

    /** 락을 적용하지 않고 그대로 실행 — 멀티 인스턴스에서 분산 락이 비활성/만료된 상황 모사. */
    private static final DistributedLockPort NO_OP_LOCK =
            new DistributedLockPort() {
                @Override
                public <T> T executeWithLock(String key, java.time.Duration w, java.time.Duration l,
                                             java.util.function.Supplier<T> action) {
                    return action.get();
                }
            };

    @Autowired ProductPersistenceAdapter productAdapter;
    @Autowired ProductVariantPersistenceAdapter variantAdapter;
    @Autowired OrderPersistenceAdapter orderAdapter;
    @Autowired OrderIdempotencyPersistenceAdapter idempotencyAdapter;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager em;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setup() {
        long n = System.nanoTime();
        userId = seedUser("buyer-" + n + "@test.com");
        productId = commit(() -> productAdapter.save(
                Product.create("상품-" + n, "설명", new BigDecimal("10000"), 100)).getId());
    }

    private IdempotentMultiItemOrderService buildService(DistributedLockPort lock) {
        var decVariant = new DecreaseVariantStockService(variantAdapter, variantAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        var decProduct = new DecreaseProductStockService(productAdapter, productAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        LoadUserForOrderPort loadUser = new LoadUserForOrderPort() {
            @Override public boolean existsById(Long id) { return true; }
            @Override public Optional<String> findEmailById(Long id) { return Optional.of("buyer@test.com"); }
        };
        SendOrderNotificationPort notify = (email, order) -> { };
        PublishOrderEventPort publish = (orderId, uid, pid, status, amount, createdAt) -> { };
        CouponUseCase coupon = Mockito.mock(CouponUseCase.class); // 쿠폰 미사용 경로
        var delegate = new CreateMultiItemOrderService(loadUser, productAdapter, variantAdapter,
                decVariant, decProduct, orderAdapter, notify, publish, coupon,
                // 옵션 스냅샷·배송비는 이 IT 의 검증 범위 밖 — 무해한 스텁
                variantId -> java.util.List.of(),
                lines -> github.lms.lemuel.shipping.domain.ShippingFeeAssessment.none());
        return new IdempotentMultiItemOrderService(delegate, lock, idempotencyAdapter, orderAdapter,
                new TransactionTemplate(txManager));
    }

    @Test
    @DisplayName("락 켜짐: 동시 20요청이 같은 키로 와도 주문 1건 — 전부 같은 주문 멱등 반환")
    void withLock_concurrentSameKey_singleOrder() throws Exception {
        runConcurrent(buildService(new InMemoryDistributedLockAdapter()), "idem-lock-" + System.nanoTime());
    }

    @Test
    @DisplayName("락 우회: DB UNIQUE 백스톱만으로도 동시 중복 제출 → 주문 1건")
    void lockBypassed_uniqueBackstop_singleOrder() throws Exception {
        runConcurrent(buildService(NO_OP_LOCK), "idem-nolock-" + System.nanoTime());
    }

    private void runConcurrent(IdempotentMultiItemOrderService service, String idempotencyKey) throws Exception {
        int threads = 20;
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(productId, null, 1));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> orderIds = ConcurrentHashMap.newKeySet();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Order o = service.create(userId, lines, null, idempotencyKey);
                    orderIds.add(o.getId());
                } catch (DuplicateOrderSubmissionException ignored) {
                    // 락 우회 시 승자 미커밋 타이밍 — 중복 미생성, 클라이언트 재시도 대상(허용)
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        boolean allReady = ready.await(30, TimeUnit.SECONDS);
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allReady).as("모든 스레드 30초 내 준비 (교착 방지 타임아웃)").isTrue();
        assertThat(finished).as("30초 내 완료").isTrue();
        assertThat(unexpected).as("예상치 못한 예외 없음").isEmpty();

        // 핵심 불변식: 주문도, 멱등 레코드도 정확히 1건. 성공 응답들은 모두 같은 주문.
        assertThat(countOrders(userId)).as("생성된 주문은 정확히 1건").isEqualTo(1L);
        assertThat(countIdempotency(idempotencyKey)).as("멱등 레코드 1건").isEqualTo(1L);
        assertThat(orderIds).as("성공 응답은 모두 동일한 단일 주문").hasSize(1);
    }

    // --- helpers -------------------------------------------------------------

    private long countOrders(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM opslab.orders WHERE user_id = ?1")
                .setParameter(1, userId).getSingleResult()).longValue();
    }

    private long countIdempotency(String key) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM opslab.order_idempotency WHERE idempotency_key = ?1")
                .setParameter(1, key).getSingleResult()).longValue();
    }

    private <T> T commit(java.util.function.Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> action.get());
    }

    private Long seedUser(String email) {
        return commit(() -> {
            em.createNativeQuery("INSERT INTO opslab.users(email, password) VALUES (?1, ?2)")
                    .setParameter(1, email).setParameter(2, "x").executeUpdate();
            Number id = (Number) em.createNativeQuery("SELECT id FROM opslab.users WHERE email = ?1")
                    .setParameter(1, email).getSingleResult();
            return id.longValue();
        });
    }
}
