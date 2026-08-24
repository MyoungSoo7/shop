package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.payment.adapter.out.order.OrderPaymentRefundAdapter;
import github.lms.lemuel.order.adapter.out.persistence.OrderPersistenceAdapter;
import github.lms.lemuel.order.adapter.out.persistence.OrderPersistenceMapperImpl;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase.PendingReclaim;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentMapper;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentPersistenceAdapter;
import github.lms.lemuel.payment.adapter.out.persistence.RefundPersistenceAdapter;
import github.lms.lemuel.payment.application.GetPaymentUseCase;
import github.lms.lemuel.payment.application.RefundPaymentUseCase;
import github.lms.lemuel.payment.application.service.RefundLifecycle;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.ProductPersistenceMapperImpl;
import github.lms.lemuel.product.adapter.out.persistence.ProductVariantPersistenceAdapter;
import github.lms.lemuel.product.application.service.DecreaseProductStockService;
import github.lms.lemuel.product.application.service.DecreaseVariantStockService;
import github.lms.lemuel.product.application.service.IncreaseProductStockService;
import github.lms.lemuel.product.application.service.IncreaseVariantStockService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환불/취소 승인 end-to-end 통합 테스트 — 실 PostgreSQL.
 *
 * <p>approveRefund/approveCancellation 이 "주문 상태만 변경"에서 나아가 실제 결제 환불(PG 스텁),
 * 주문 상태 확정, 재고 원복까지 <b>하나의 흐름</b>으로 이어지는지 커밋된 DB 상태로 검증한다.
 * PG 외부호출은 성공 스텁으로 대체하고, payment→order 상태 반영은 실제 어댑터와 동형인 람다로 배선한다.
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
        PaymentPersistenceAdapter.class, PaymentMapper.class, RefundPersistenceAdapter.class,
        OutboxSchema.class})
@ActiveProfiles("test")
class RefundApprovalIT {

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
    @Autowired OrderPersistenceAdapter orderAdapter;
    @Autowired PaymentPersistenceAdapter paymentAdapter;
    @Autowired RefundPersistenceAdapter refundAdapter;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager em;

    private CreateMultiItemOrderService createOrderService;
    private ChangeOrderStatusService changeStatusService;

    @BeforeEach
    void setup() {
        var decVariant = new DecreaseVariantStockService(variantAdapter, variantAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        var decProduct = new DecreaseProductStockService(productAdapter, productAdapter,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        LoadUserForOrderPort loadUser = new LoadUserForOrderPort() {
            @Override public boolean existsById(Long id) { return true; }
            @Override public Optional<String> findEmailById(Long id) { return Optional.of("buyer@test.com"); }
        };
        SendOrderNotificationPort notify = (email, order) -> { };
        PublishOrderEventPort publishOrder = (orderId, uid, pid, status, amount, createdAt) -> { };
        CouponUseCase coupon = Mockito.mock(CouponUseCase.class); // 쿠폰 미사용 경로
        createOrderService = new CreateMultiItemOrderService(loadUser, productAdapter, variantAdapter,
                decVariant, decProduct, orderAdapter, notify, publishOrder, coupon,
                // 옵션 스냅샷·배송비는 이 IT 의 검증 범위 밖 — 무해한 스텁
                variantId -> java.util.List.of(),
                lines -> github.lms.lemuel.shipping.domain.ShippingFeeAssessment.none());

        // payment→order 상태 반영: 실제 OrderAdapter(→ ChangeOrderStatusService.updateStatus)와 동형인 람다.
        // (수동 조립에서 순환 의존을 끊기 위한 대체 — load → transitionTo → save 로 동일 효과)
        UpdateOrderStatusPort updateOrderStatus = (orderId, status) -> {
            Order o = orderAdapter.findById(orderId).orElseThrow();
            o.transitionTo(OrderStatus.valueOf(status));
            orderAdapter.save(o);
        };
        PgClientPort pgStub = new PgClientPort() {
            @Override public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) { return "pg-tx"; }
            @Override public void capture(String pgTransactionId, BigDecimal amount) { }
            @Override public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) { /* PG 환불 성공 스텁 */ }
        };
        PublishEventPort publishPayment = new PublishEventPort() {
            @Override public void publishPaymentCreated(Long paymentId, Long orderId) { }
            @Override public void publishPaymentAuthorized(Long paymentId) { }
            @Override public void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount,
                                                         java.time.LocalDateTime capturedAt,
                                                         String paymentMethod, String pgTransactionId,
                                                         github.lms.lemuel.payment.application.port.out.SellerSettlementMeta sellerMeta) { }
            @Override public void publishPaymentRefunded(Long paymentId, Long orderId, BigDecimal refundedAmount,
                                                         BigDecimal refundAmount, Long refundId) { }
        };

        // 수동 조립이라 @Transactional(REQUIRES_NEW) 프록시가 없어 begin/fail 은 호출 트랜잭션에서 실행된다.
        // 이 IT 는 PG 성공 경로만 검증하므로(실패 롤백·독립 커밋 미검증) 동작에 문제없다.
        var refundLifecycle = new RefundLifecycle(refundAdapter, refundAdapter,
                new github.lms.lemuel.common.opssignal.NoOpOpsSignalPublisher());
        // 현금영수증도 이 시나리오의 관심사가 아니다 — 카드 결제라 애초에 발급 대상이 아니다.
        github.lms.lemuel.payment.application.port.in.CashReceiptUseCase noopCashReceipt =
                new github.lms.lemuel.payment.application.port.in.CashReceiptUseCase() {
                    @Override public github.lms.lemuel.payment.domain.CashReceipt issue(IssueCommand c) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public github.lms.lemuel.payment.domain.CashReceipt issueForOrder(
                            Long orderId, Long requesterUserId,
                            github.lms.lemuel.payment.domain.CashReceiptPurpose purpose,
                            github.lms.lemuel.payment.domain.CashReceiptIdentifier.Type type,
                            String value) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public java.util.Optional<github.lms.lemuel.payment.domain.CashReceipt>
                            findActiveByPayment(Long paymentId, Long requesterUserId) {
                        return java.util.Optional.empty();
                    }
                    @Override public java.util.Optional<github.lms.lemuel.payment.domain.CashReceipt>
                            findActiveByOrder(Long orderId, Long requesterUserId) {
                        return java.util.Optional.empty();
                    }
                    @Override public void cancelForPayment(Long paymentId, String reason) { }
                };
        var refundUseCase = new RefundPaymentUseCase(paymentAdapter, paymentAdapter, pgStub,
                updateOrderStatus, publishPayment, refundAdapter, refundAdapter, refundLifecycle,
                noopCashReceipt);
        var getPayment = new GetPaymentUseCase(paymentAdapter);
        RefundOrderPaymentPort refundOrderPaymentPort =
                new OrderPaymentRefundAdapter(getPayment, refundUseCase);
        var increaseProduct = new IncreaseProductStockService(productAdapter);
        var increaseVariant = new IncreaseVariantStockService(variantAdapter);
        SaveOrderStatusHistoryPort history = (orderId, from, to, by, reason) -> { };

        // 포인트 적립은 이 시나리오의 관심사가 아니다 — 호출됐다는 사실만 안전하게 흘려보낸다.
        github.lms.lemuel.order.application.port.out.OrderPointRewardPort noopReward =
                new github.lms.lemuel.order.application.port.out.OrderPointRewardPort() {
                    @Override public void earnOnDelivered(github.lms.lemuel.order.domain.Order order) { }
                    @Override public void revokeOnCanceled(github.lms.lemuel.order.domain.Order order) { }
                };
        // 쿠폰 회수도 이 시나리오의 관심사가 아니다(이 주문은 쿠폰을 쓰지 않았다).
        github.lms.lemuel.order.application.port.out.OrderCouponRestorePort noopCoupon =
                (orderId, reason) -> { };
        changeStatusService = new ChangeOrderStatusService(orderAdapter, orderAdapter, history,
                refundOrderPaymentPort, increaseProduct, increaseVariant, noopReward, noopCoupon);
    }

    @Test
    @DisplayName("환불 승인(배송 전): 전액 환불 → 주문 REFUNDED, 결제 REFUNDED, 재고 원복")
    void approveRefund_beforeShipping_fullRefund() {
        Fixture f = seedPaidOrder(/*stock*/100, /*price*/new BigDecimal("10000"), /*qty*/2);
        prepareOrder(f.orderId, BigDecimal.ZERO, /*ship*/false, OrderStatus.REFUND_REQUESTED);

        inNewTx(() -> changeStatusService.approveRefund(f.orderId, "변심", "admin"));

        assertThat(orderAdapter.findById(f.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        PaymentDomain pay = paymentAdapter.loadByOrderId(f.orderId).orElseThrow();
        assertThat(pay.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(pay.getRefundedAmount()).isEqualByComparingTo("20000");
        assertThat(productAdapter.findById(f.productId).orElseThrow().getStockQuantity())
                .as("재고 원복(98→100)").isEqualTo(100);
    }

    @Test
    @DisplayName("환불 승인(배송 후): 배송비 차감 부분 환불 → 주문 REFUNDED, 결제 부분환불, 재고는 회수 전까지 보류")
    void approveRefund_afterShipping_deductsShippingFee() {
        Fixture f = seedPaidOrder(100, new BigDecimal("10000"), 2); // amount 20000
        prepareOrder(f.orderId, new BigDecimal("3000"), /*ship*/true, OrderStatus.REFUND_REQUESTED);

        inNewTx(() -> changeStatusService.approveRefund(f.orderId, "배송후 반품", "admin"));

        assertThat(orderAdapter.findById(f.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        PaymentDomain pay = paymentAdapter.loadByOrderId(f.orderId).orElseThrow();
        // 배송비 3000 차감 → 17000 만 환불. 결제는 잔액(3000)이 남아 CAPTURED 유지.
        assertThat(pay.getRefundedAmount()).isEqualByComparingTo("17000");
        assertThat(pay.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        // 배송된 물건은 고객 손에 있다 — 환불만으로 되돌리면 장부재고가 실재고를 넘어 초과판매가 난다.
        // 실제 회수(반품)가 확인될 때 비로소 판매 가능 재고로 복귀한다(ShippingService.markReturned).
        assertThat(productAdapter.findById(f.productId).orElseThrow().getStockQuantity())
                .as("재고 보류(98 유지)").isEqualTo(98);
    }

    @Test
    @DisplayName("배송 후 환불로 보류된 재고는 반품 회수가 확인될 때 원복된다")
    void stockReturnsOnlyAfterGoodsAreReclaimed() {
        Fixture f = seedPaidOrder(100, new BigDecimal("10000"), 2); // 재고 100 → 98
        prepareOrder(f.orderId, new BigDecimal("3000"), /*ship*/true, OrderStatus.REFUND_REQUESTED);

        inNewTx(() -> changeStatusService.approveRefund(f.orderId, "배송후 반품", "admin"));
        assertThat(productAdapter.findById(f.productId).orElseThrow().getStockQuantity())
                .as("회수 전에는 보류").isEqualTo(98);

        // 택배 회수 완료 — 물건이 실제로 돌아온 시점.
        boolean restored = inNewTx(() -> changeStatusService.restoreStockOnReturn(f.orderId));
        assertThat(restored).as("회수 시점에 원복이 실행됨").isTrue();
        // @DataJpaTest 는 테스트 메서드를 트랜잭션으로 감싸 영속성 컨텍스트를 공유한다.
        // 위에서 읽어 캐시된 상품/주문이 그대로 돌아오므로, 커밋된 값을 보려면 1차 캐시를 비운다.
        em.clear();

        assertThat(productAdapter.findById(f.productId).orElseThrow().getStockQuantity())
                .as("회수 후 원복(98→100)").isEqualTo(100);

        // 회수 신호가 중복 도착해도 재고가 더 늘지 않는다(멱등).
        boolean again = inNewTx(() -> changeStatusService.restoreStockOnReturn(f.orderId));
        assertThat(again).as("두 번째 회수 신호는 원복하지 않음").isFalse();
        em.clear();   // 캐시된 100 이 아니라 커밋된 값을 본다(어서션이 무의미해지지 않도록)
        assertThat(productAdapter.findById(f.productId).orElseThrow().getStockQuantity())
                .as("중복 회수 신호에도 불변").isEqualTo(100);
    }

    @Test
    @DisplayName("회수 대기 조회: 보류된 주문만 잡히고, 회수되면 목록에서 빠진다")
    void pendingStockReclaimQuery() {
        Fixture shippedRefund = seedPaidOrder(100, new BigDecimal("10000"), 2);
        prepareOrder(shippedRefund.orderId, new BigDecimal("3000"), /*ship*/true, OrderStatus.REFUND_REQUESTED);
        inNewTx(() -> changeStatusService.approveRefund(shippedRefund.orderId, "배송후 반품", "admin"));

        // 대조군 — 배송 전 환불이라 이미 원복됐고, 회수 대기가 아니다.
        Fixture beforeShipping = seedPaidOrder(100, new BigDecimal("10000"), 1);
        prepareOrder(beforeShipping.orderId, BigDecimal.ZERO, /*ship*/false, OrderStatus.REFUND_REQUESTED);
        inNewTx(() -> changeStatusService.approveRefund(beforeShipping.orderId, "변심", "admin"));
        em.clear();

        var reclaimService = new GetPendingStockReclaimService(orderAdapter);
        var pending = inNewTx(() -> reclaimService.findPending(LocalDateTime.now(), 100));

        assertThat(pending).extracting(PendingReclaim::orderId)
                .as("배송 후 환불만 회수 대기").contains(shippedRefund.orderId)
                .doesNotContain(beforeShipping.orderId);
        assertThat(pending.stream()
                .filter(r -> r.orderId().equals(shippedRefund.orderId)).findFirst().orElseThrow()
                .totalQuantity()).as("묶인 수량").isEqualTo(2);

        // 택배 회수가 확정되면 재고가 돌아오고 목록에서도 빠진다.
        inNewTx(() -> changeStatusService.restoreStockOnReturn(shippedRefund.orderId));
        em.clear();

        assertThat(inNewTx(() -> reclaimService.findPending(LocalDateTime.now(), 100)))
                .extracting(PendingReclaim::orderId)
                .as("회수 후 목록에서 제외").doesNotContain(shippedRefund.orderId);
    }

    @Test
    @DisplayName("취소 승인(결제됨): 전액 환불 → 주문 REFUNDED, 결제 REFUNDED, 재고 원복")
    void approveCancellation_paid_refunds() {
        Fixture f = seedPaidOrder(100, new BigDecimal("10000"), 2);
        prepareOrder(f.orderId, BigDecimal.ZERO, false, OrderStatus.CANCELLATION_REQUESTED);

        inNewTx(() -> changeStatusService.approveCancellation(f.orderId, "주문 취소", "admin"));

        assertThat(orderAdapter.findById(f.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(paymentAdapter.loadByOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(productAdapter.findById(f.productId).orElseThrow().getStockQuantity())
                .as("재고 원복").isEqualTo(100);
    }

    // --- fixtures & helpers --------------------------------------------------

    private record Fixture(Long orderId, Long productId) {}

    /** 사용자·상품 시드 → 다건 주문 생성(재고 차감) → CAPTURED 결제 시드. */
    private Fixture seedPaidOrder(int stock, BigDecimal price, int qty) {
        long n = System.nanoTime();
        Long userId = seedUser("buyer-" + n + "@test.com");
        Long productId = commit(() -> productAdapter.save(
                Product.create("상품-" + n, "설명", price, stock)).getId());

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(productId, null, qty));
        Order saved = inNewTx(() -> createOrderService.create(userId, lines, null));

        PaymentDomain pay = PaymentDomain.rehydrate(null, saved.getId(), saved.getAmount(), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-" + n,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        commit(() -> paymentAdapter.save(pay));
        return new Fixture(saved.getId(), productId);
    }

    /** 주문을 배송/취소 상태로 전이시키고 배송비를 설정한다. */
    private void prepareOrder(Long orderId, BigDecimal shippingFee, boolean shipped, OrderStatus target) {
        commit(() -> {
            Order o = orderAdapter.findById(orderId).orElseThrow();
            o.assignShippingFee(shippingFee);
            o.transitionTo(OrderStatus.PAID);
            if (target == OrderStatus.CANCELLATION_REQUESTED) {
                o.transitionTo(OrderStatus.CANCELLATION_REQUESTED);
            } else if (shipped) {
                o.transitionTo(OrderStatus.SHIPPING_PENDING);
                o.transitionTo(OrderStatus.IN_TRANSIT);       // shipped=true
                o.transitionTo(OrderStatus.REFUND_REQUESTED);
            } else {
                o.transitionTo(OrderStatus.REFUND_REQUESTED);
            }
            return orderAdapter.save(o);
        });
    }

    private <T> T inNewTx(Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> action.get());
    }

    private void inNewTx(Runnable action) {
        inNewTx(() -> { action.run(); return null; });
    }

    private <T> T commit(Supplier<T> action) {
        return inNewTx(action);
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
