package github.lms.lemuel.product.application.service;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceMapperImpl;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옵션 없는 <b>일반 상품</b> 재고 차감 동시성 통합 테스트 (SKU/variant 경로와 별개).
 *
 * <p><b>시나리오</b>(리뷰 피드백 "재고 10개에 100개 주문 요청"):
 * <pre>
 *   초기 재고: 10
 *   동시 요청: 100 스레드가 각각 1 개씩 차감(CountDownLatch 로 동시 시작)
 *   기대: 정확히 10 건 성공 · 90 건 InsufficientStock · 최종 재고 0 (음수 없음)
 * </pre>
 *
 * <p>{@link DecreaseProductStockService} 의 원자적 조건부 UPDATE
 * ({@code UPDATE ... SET stock = stock - q WHERE id = ? AND stock >= q})가 락 대기·재시도 없이
 * 보유 수량만큼만 성공시켜 초과판매를 막는지를 실제 PostgreSQL 에서 검증한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductPersistenceAdapter.class,
        github.lms.lemuel.category.adapter.out.persistence.PrimaryCategoryLookupAdapter.class, ProductPersistenceMapperImpl.class, OutboxSchema.class})
@ActiveProfiles("test")
class ProductStockConcurrencyIT {

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

    @Autowired ProductPersistenceAdapter persistenceAdapter;
    @Autowired PlatformTransactionManager txManager;

    private DecreaseProductStockService service;
    private Long productId;

    @BeforeEach
    void setup() {
        // 시드는 테스트의 롤백 트랜잭션 밖(REQUIRES_NEW)에서 커밋해야 워커 스레드가 볼 수 있다.
        TransactionTemplate seedTx = new TransactionTemplate(txManager);
        seedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        productId = seedTx.execute(s -> persistenceAdapter.save(
                Product.create("동시성-상품-" + System.nanoTime(), "재고 10", new BigDecimal("10000"), 10))
                .getId());

        service = new DecreaseProductStockService(persistenceAdapter, persistenceAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("동시 100 스레드가 재고 10 상품을 차감 — 정확히 10 건 성공, 90 건 InsufficientStock, 최종 재고 0")
    void concurrentDecrease_preservesIntegrity() throws Exception {
        final int threads = 100;
        final int initialStock = 10;

        // startLatch 배리어에서 threads 개 태스크 전원이 풀 스레드를 점유한 채 대기하므로,
        // 풀 크기가 threads 미만이면 readyLatch 가 끝까지 내려가지 못해 교착한다 — 반드시 threads 개.
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    service.decrease(productId, 1);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    insufficientCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean allReady = readyLatch.await(30, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(allReady).as("모든 스레드 30초 내 준비 (교착 방지 타임아웃)").isTrue();
        assertThat(finished).as("모든 스레드 30초 내 완료").isTrue();
        assertThat(unexpectedErrors).as("InsufficientStock 외 예상치 못한 예외 — 원자 차감 일관성 위반").isEmpty();
        assertThat(successCount.get()).as("성공한 차감 수").isEqualTo(initialStock);
        assertThat(insufficientCount.get()).as("재고 부족으로 거절된 수").isEqualTo(threads - initialStock);

        Product finalState = persistenceAdapter.findById(productId).orElseThrow();
        assertThat(finalState.getStockQuantity()).as("최종 재고는 정확히 0 — 음수 아님").isZero();
    }
}
