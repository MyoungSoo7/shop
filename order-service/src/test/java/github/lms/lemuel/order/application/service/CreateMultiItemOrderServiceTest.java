package github.lms.lemuel.order.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import github.lms.lemuel.product.application.port.in.DecreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CreateMultiItemOrderServiceTest {

    @Mock LoadUserForOrderPort loadUserPort;
    @Mock LoadProductPort loadProductPort;
    @Mock LoadProductVariantPort loadVariantPort;
    @Mock DecreaseVariantStockUseCase decreaseStockUseCase;
    @Mock DecreaseProductStockUseCase decreaseProductStockUseCase;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SendOrderNotificationPort sendNotificationPort;
    @Mock github.lms.lemuel.order.application.port.out.PublishOrderEventPort publishOrderEventPort;
    @Mock CouponUseCase couponUseCase;
    @Mock github.lms.lemuel.product.application.port.in.DescribeVariantOptionsUseCase describeVariantOptionsUseCase;
    @Mock github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase assessShippingFeeUseCase;
    @InjectMocks CreateMultiItemOrderService service;

    @org.junit.jupiter.api.BeforeEach
    void noShippingFeeByDefault() {
        when(assessShippingFeeUseCase.assess(any()))
                .thenReturn(github.lms.lemuel.shipping.domain.ShippingFeeAssessment.none());
    }

    private Product mockProduct(Long id, String name, BigDecimal price) {
        Product p = Product.create(name, "설명", price, 100);
        // use reflection or mock
        Product spy = spy(p);
        when(spy.getId()).thenReturn(id);
        return spy;
    }

    @Test @DisplayName("create: 상품만 있는 주문 성공 (variant 없음)")
    void create_noVariant() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2));
        Order result = service.create(1L, lines);
        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        // 옵션 없는 일반 상품도 재고 차감되어야 한다 (variant 경로는 미진입)
        verify(decreaseProductStockUseCase).decrease(10L, 2);
        verify(decreaseStockUseCase, never()).decrease(any(), anyInt());
        verify(sendNotificationPort).sendOrderConfirmation(eq("user@test.com"), any());
    }

    @Test @DisplayName("create: 산정된 배송비가 결제 금액에 더해지고 주문에 보존된다")
    void create_appliesShippingFee() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assessShippingFeeUseCase.assess(any())).thenReturn(
                new github.lms.lemuel.shipping.domain.ShippingFeeAssessment(
                        new BigDecimal("3000"), List.of()));

        Order result = service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2)));

        assertThat(result.getAmount()).isEqualByComparingTo("23000"); // 20000 + 3000
        assertThat(result.getShippingFee()).isEqualByComparingTo("3000");
    }

    @Test @DisplayName("create: 배송비 산정에는 쿠폰 할인 전 라인 금액이 전달된다")
    void create_shippingAssessedOnPreDiscountAmount() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(couponUseCase.validateCoupon(eq("SALE"), eq(1L), any()))
                .thenReturn(new CouponUseCase.ValidateResult(true, "ok", new BigDecimal("5000"),
                        new BigDecimal("15000"), null));

        service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2)), "SALE");

        verify(assessShippingFeeUseCase).assess(argThat(lines ->
                lines.size() == 1
                        && lines.get(0).lineAmount().compareTo(new BigDecimal("20000")) == 0));
    }

    @Test @DisplayName("create: SKU 있는 주문 — 재고 차감")
    void create_withVariant() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));

        ProductVariant variant = ProductVariant.create(10L, "SKU-001", "빨강", new BigDecimal("1000"), 50);
        ProductVariant variantSpy = spy(variant);
        when(variantSpy.getProductId()).thenReturn(10L);
        when(loadVariantPort.loadById(20L)).thenReturn(Optional.of(variantSpy));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, 20L, 1));
        Order result = service.create(1L, lines);
        assertThat(result).isNotNull();
        verify(decreaseStockUseCase).decrease(20L, 1);
        // SKU 라인은 일반 상품 재고 차감 경로를 타지 않는다
        verify(decreaseProductStockUseCase, never()).decrease(any(), anyInt());
    }

    @Test @DisplayName("create: 사용자 없으면 예외")
    void create_userNotFound() {
        when(loadUserPort.findEmailById(99L)).thenReturn(Optional.empty());
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(99L, lines))
                .isInstanceOf(UserNotExistsException.class);
    }

    @Test @DisplayName("create: 상품 없으면 예외")
    void create_productNotFound() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.empty());
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("create+쿠폰: 검증 성공 → 할인 반영 + 같은 트랜잭션에서 사용 기록")
    void create_withValidCoupon_appliesDiscountAndRecordsUsage() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.assignId(500L);
            return o;
        });
        // 소계 20,000 (10,000 x 2) 기준 2,000원 할인
        when(couponUseCase.validateCoupon(eq("SAVE2000"), eq(1L), any()))
                .thenReturn(new CouponUseCase.ValidateResult(
                        true, "ok", new BigDecimal("2000"), new BigDecimal("18000"), null));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2));
        Order result = service.create(1L, lines, "SAVE2000");

        // amount = 소계 20,000 - 할인 2,000 = 18,000
        assertThat(result.getAmount()).isEqualByComparingTo("18000");
        // 검증은 소계(20,000) 기준으로 호출
        verify(couponUseCase).validateCoupon("SAVE2000", 1L, new BigDecimal("20000"));
        // 사용 기록은 저장된 orderId 로, 같은 흐름에서 호출
        verify(couponUseCase).useCoupon("SAVE2000", 1L, 500L);
    }

    @Test @DisplayName("create+쿠폰: 검증 실패 → 예외 + 주문 저장/쿠폰 사용 모두 미수행(롤백)")
    void create_withInvalidCoupon_throwsAndDoesNotSaveOrder() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(couponUseCase.validateCoupon(eq("EXPIRED"), eq(1L), any()))
                .thenReturn(new CouponUseCase.ValidateResult(
                        false, "만료된 쿠폰입니다.", BigDecimal.ZERO, new BigDecimal("10000"), null));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines, "EXPIRED"))
                .isInstanceOf(CreateMultiItemOrderService.CouponApplicationException.class)
                .hasMessageContaining("만료된 쿠폰");

        verify(saveOrderPort, never()).save(any());
        verify(couponUseCase, never()).useCoupon(any(), any(), any());
        verify(publishOrderEventPort, never()).publishOrderCreated(any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("create+쿠폰: 사용 기록 실패(한도/중복) → 예외 전파로 트랜잭션 롤백")
    void create_couponUseFails_propagatesForRollback() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(couponUseCase.validateCoupon(eq("LIMIT"), eq(1L), any()))
                .thenReturn(new CouponUseCase.ValidateResult(
                        true, "ok", new BigDecimal("1000"), new BigDecimal("9000"), null));
        doThrow(new IllegalStateException("쿠폰 사용 한도를 초과했습니다."))
                .when(couponUseCase).useCoupon(eq("LIMIT"), eq(1L), any());

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines, "LIMIT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한도");
        // 사용 기록 실패는 useCoupon 이후 — 이벤트 발행까지 도달하지 않아야 롤백 의미가 산다
        verify(publishOrderEventPort, never()).publishOrderCreated(any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("create: 쿠폰 코드 없으면(null) 쿠폰 경로 미진입")
    void create_noCoupon_skipsCouponFlow() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        service.create(1L, lines, null);

        verifyNoInteractions(couponUseCase);
    }

    @Test @DisplayName("create: variant가 product에 속하지 않으면 예외")
    void create_variantMismatch() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));

        ProductVariant variant = ProductVariant.create(99L, "SKU", "opt", BigDecimal.ZERO, 10);
        ProductVariant variantSpy = spy(variant);
        when(variantSpy.getProductId()).thenReturn(99L); // different product
        when(loadVariantPort.loadById(20L)).thenReturn(Optional.of(variantSpy));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, 20L, 1));
        assertThatThrownBy(() -> service.create(1L, lines))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("variant 가 product 에 속하지 않음");
    }
}
