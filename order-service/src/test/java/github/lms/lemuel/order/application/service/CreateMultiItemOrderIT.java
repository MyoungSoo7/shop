package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.coupon.adapter.out.persistence.CouponPersistenceAdapter;
import github.lms.lemuel.coupon.application.service.CouponService;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.adapter.out.persistence.OrderPersistenceAdapter;
import github.lms.lemuel.order.adapter.out.persistence.OrderPersistenceMapperImpl;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItemOption;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceMapperImpl;
import github.lms.lemuel.product.adapter.out.persistence.OptionCatalogPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.ProductVariantPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.VariantOptionMappingPersistenceAdapter;
import github.lms.lemuel.product.application.service.DescribeVariantOptionsService;
import github.lms.lemuel.product.application.service.DecreaseProductStockService;
import github.lms.lemuel.product.application.service.DecreaseVariantStockService;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 다건 주문 생성 결합 통합 테스트 — 실 PostgreSQL 로 "기능들이 주문 흐름 안에서 함께 동작하는가" 검증.
 *
 * <p>옵션 단가(추가금·정액·정률 할인) → 소계 계산 → 쿠폰 검증·할인 → 주문 저장 → 옵션 재고 차감 →
 * 쿠폰 사용 기록이 <b>하나의 트랜잭션</b>에서 묶여 동작하는지, 그리고 쿠폰 실패 시 <b>전부 롤백</b>되는지를
 * 단위 모킹이 아닌 실제 DB 상태로 확인한다.
 *
 * <p>구성: {@code @DataJpaTest} 슬라이스에 실제 영속 어댑터들을 import 하고, 서비스는 트랜잭션 경계를
 * 명시적으로 통제하려고 수동 조립한다. 서비스 호출은 {@code REQUIRES_NEW} 로 감싸 독립 커밋/롤백시키고
 * (서비스 자체 {@code @Transactional} 은 프록시 없이도 이 외부 경계가 대체), 시드/검증은 별도 커밋 트랜잭션에서 수행한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductPersistenceAdapter.class,
        github.lms.lemuel.category.adapter.out.persistence.PrimaryCategoryLookupAdapter.class, ProductPersistenceMapperImpl.class,
        ProductVariantPersistenceAdapter.class, CouponPersistenceAdapter.class,
        OrderPersistenceAdapter.class, OrderPersistenceMapperImpl.class, OutboxSchema.class,
        OptionCatalogPersistenceAdapter.class, VariantOptionMappingPersistenceAdapter.class})
@ActiveProfiles("test")
class CreateMultiItemOrderIT {

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

    @Autowired ProductPersistenceAdapter productAdapter;
    @Autowired ProductVariantPersistenceAdapter variantAdapter;
    @Autowired CouponPersistenceAdapter couponAdapter;
    @Autowired OrderPersistenceAdapter orderAdapter;
    @Autowired OptionCatalogPersistenceAdapter optionCatalogAdapter;
    @Autowired VariantOptionMappingPersistenceAdapter variantOptionMappingAdapter;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager em;

    private CreateMultiItemOrderService orderService;

    @BeforeEach
    void setup() {
        var decVariant = new DecreaseVariantStockService(variantAdapter, variantAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        var decProduct = new DecreaseProductStockService(productAdapter, productAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        var couponService = new CouponService(couponAdapter, couponAdapter,
                java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul")));

        // 사이드이펙트 포트는 통합 검증 범위 밖이라 무해한 스텁으로 대체 (이메일/알림/이벤트 발행)
        LoadUserForOrderPort loadUser = new LoadUserForOrderPort() {
            @Override public boolean existsById(Long userId) { return true; }
            @Override public Optional<String> findEmailById(Long userId) { return Optional.of("buyer@test.com"); }
        };
        SendOrderNotificationPort notify = (email, order) -> { };
        PublishOrderEventPort publish = (orderId, userId, productId, status, amount, createdAt) -> { };

        // 옵션 스냅샷은 실제 경로로 검증한다 — 매핑이 없으면 표시명 파싱으로 폴백한다.
        var describeOptions = new DescribeVariantOptionsService(
                variantAdapter, optionCatalogAdapter, variantOptionMappingAdapter);

        orderService = new CreateMultiItemOrderService(loadUser, productAdapter, variantAdapter,
                decVariant, decProduct, orderAdapter, notify, publish, couponService, describeOptions,
                // 배송비는 이 IT 의 검증 범위 밖 — 부과 없음으로 고정
                lines -> github.lms.lemuel.shipping.domain.ShippingFeeAssessment.none());
    }

    @Test
    @DisplayName("결합 흐름: 옵션 단가(추가금·정액·정률) + 쿠폰 할인 + 재고 차감 + 사용 기록이 한 트랜잭션에서 커밋")
    void fullFlow_appliesOptionPriceCouponDiscountAndStock() {
        long n = System.nanoTime();
        Long userId = seedUser("buyer-" + n + "@test.com");
        Long productId = commit(() -> productAdapter.save(
                Product.create("상품-" + n, "설명", new BigDecimal("10000"), 100)).getId());
        // 추가금 1000, 정액할인 500, 정률할인 10% → 유효단가 (10000+1000-500)=10500, -10% = 9450
        Long variantId = commit(() -> variantAdapter.save(ProductVariant.rehydrate(
                null, productId, "SKU-" + n, "색상:빨강/사이즈:L",
                new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("10"), 50, 0L,
                ProductVariantStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now())).getId());
        Long couponId = commit(() -> couponAdapter.save(Coupon.create("SAVE10-" + n,
                CouponType.PERCENTAGE, new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("5000"), 100, LocalDateTime.now().plusDays(30))).getId());
        String couponCode = "SAVE10-" + n;

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(productId, variantId, 2));
        Order saved = inNewTx(() -> orderService.create(userId, lines, couponCode));

        // 소계 = 9450 * 2 = 18900, 쿠폰 10% = 1890 (상한 5000 미만), 최종 = 17010
        assertThat(saved.getAmount()).isEqualByComparingTo("17010");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().getFirst().getUnitPrice()).isEqualByComparingTo("9450");

        // 옵션 스냅샷: 카탈로그 미백필 SKU 라 표시명 파싱 폴백으로 채워지고, 저장 후 다시 읽어도 남아 있다
        assertThat(saved.getItems().getFirst().describeOptions()).isEqualTo("색상: 빨강 / 사이즈: L");
        Order reloaded = orderAdapter.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems().getFirst().getOptions())
                .extracting(OrderItemOption::getAxisCode, OrderItemOption::getValueName)
                .containsExactly(tuple("색상", "빨강"), tuple("사이즈", "L"));

        // 커밋된 DB 상태 검증
        assertThat(variantAdapter.loadById(variantId).orElseThrow().getStockQuantity())
                .as("옵션 재고 차감 반영").isEqualTo(48);
        assertThat(couponAdapter.hasUserUsedCoupon(couponId, userId))
                .as("쿠폰 사용 기록 생성").isTrue();
        assertThat(orderAdapter.findById(saved.getId())).as("주문 영속").isPresent();
    }

    @Test
    @DisplayName("롤백: 쿠폰 검증 실패 시 주문·옵션 재고 차감·사용 기록이 전부 롤백")
    void couponFailure_rollsBackEverything() {
        long n = System.nanoTime();
        Long userId = seedUser("buyer2-" + n + "@test.com");
        Long productId = commit(() -> productAdapter.save(
                Product.create("상품2-" + n, "설명", new BigDecimal("10000"), 100)).getId());
        Long variantId = commit(() -> variantAdapter.save(ProductVariant.create(
                productId, "SKU2-" + n, "색상:파랑", new BigDecimal("1000"), 50)).getId());
        // 최소 주문금액이 소계(11,000)를 크게 웃돌아 검증 실패하는 쿠폰
        // (min_order_amount 는 NUMERIC(10,2) — 정수부 최대 8자리)
        Long couponId = commit(() -> couponAdapter.save(Coupon.create("MINBIG-" + n,
                CouponType.PERCENTAGE, new BigDecimal("10"), new BigDecimal("99999999"),
                null, 100, LocalDateTime.now().plusDays(30))).getId());
        String couponCode = "MINBIG-" + n;

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(productId, variantId, 1));

        assertThatThrownBy(() -> inNewTx(() -> orderService.create(userId, lines, couponCode)))
                .isInstanceOf(CreateMultiItemOrderService.CouponApplicationException.class);

        // 재고 차감은 쿠폰 검증보다 먼저 일어나지만, 같은 트랜잭션이라 롤백으로 원복되어야 한다
        assertThat(variantAdapter.loadById(variantId).orElseThrow().getStockQuantity())
                .as("재고 원복(초기값 유지)").isEqualTo(50);
        assertThat(couponAdapter.hasUserUsedCoupon(couponId, userId))
                .as("쿠폰 사용 기록 없음").isFalse();
    }

    // --- helpers -------------------------------------------------------------

    /** 새 트랜잭션에서 실행하고 커밋 (서비스 호출용 — 예외 시 롤백). */
    private <T> T inNewTx(java.util.function.Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> action.get());
    }

    /** 시드 데이터를 독립 커밋한다 (REQUIRES_NEW). */
    private <T> T commit(java.util.function.Supplier<T> action) {
        return inNewTx(action);
    }

    /** users FK(coupon_usages.user_id) 충족용 사용자 1행 시드 후 id 반환. */
    private Long seedUser(String email) {
        return commit(() -> {
            em.createNativeQuery("INSERT INTO opslab.users(email, password) VALUES (?1, ?2)")
                    .setParameter(1, email)
                    .setParameter(2, "x")
                    .executeUpdate();
            Number id = (Number) em.createNativeQuery(
                            "SELECT id FROM opslab.users WHERE email = ?1")
                    .setParameter(1, email)
                    .getSingleResult();
            return id.longValue();
        });
    }
}
