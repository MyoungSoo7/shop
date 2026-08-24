package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.OrderCouponRestorePort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase;
import github.lms.lemuel.shipping.domain.ShippingFeeAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 라인 단위 부분 취소 + 배송비 재부과.
 *
 * <p>핵심 규칙(SSG B2E 실무 이식): 무료배송 임계를 채우던 상품이 취소로 빠지면 면제됐던 배송비가
 * 되살아나고, 그 재부과분은 환불액에서 차감된다. 이 규칙이 없으면 "5 만원어치 주문 → 무료배송 →
 * 4 만원어치 취소 → 1 만원짜리 상품을 배송비 없이 받는" 구멍이 생긴다.
 */
@DisplayName("CancelOrderItemsService — 부분 취소와 배송비 재부과")
class CancelOrderItemsServiceTest {

    private LoadOrderPort loadOrderPort;
    private SaveOrderPort saveOrderPort;
    private SaveOrderStatusHistoryPort historyPort;
    private RefundOrderPaymentPort refundPort;
    private IncreaseProductStockUseCase increaseProductStock;
    private IncreaseVariantStockUseCase increaseVariantStock;
    private AssessShippingFeeUseCase assessShippingFee;
    private OrderCouponRestorePort couponRestorePort;
    private CancelOrderItemsService service;

    @BeforeEach
    void setUp() {
        loadOrderPort = mock(LoadOrderPort.class);
        saveOrderPort = mock(SaveOrderPort.class);
        historyPort = mock(SaveOrderStatusHistoryPort.class);
        refundPort = mock(RefundOrderPaymentPort.class);
        increaseProductStock = mock(IncreaseProductStockUseCase.class);
        increaseVariantStock = mock(IncreaseVariantStockUseCase.class);
        assessShippingFee = mock(AssessShippingFeeUseCase.class);
        couponRestorePort = mock(OrderCouponRestorePort.class);
        service = new CancelOrderItemsService(loadOrderPort, saveOrderPort, historyPort, refundPort,
                increaseProductStock, increaseVariantStock, assessShippingFee, couponRestorePort);
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 40,000 + 10,000 = 50,000 → 무료배송(임계 50,000)으로 배송비 0 인 결제 완료 주문. */
    private Order freeShippedOrder() {
        OrderItem big = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("40000"), 1);
        OrderItem small = OrderItem.newItem(200L, 300L, "SKU-1", "상품B", new BigDecimal("10000"), 1);
        big.assignId(1L);
        small.assignId(2L);
        Order order = Order.createMultiItem(9L, List.of(big, small), BigDecimal.ZERO, BigDecimal.ZERO);
        order.assignId(77L);
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(77L)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("무료배송 조건이 깨지면 배송비가 되살아나고 그만큼 환불액에서 차감된다")
    void shippingFeeRevivesAndReducesRefund() {
        Order order = freeShippedOrder();
        // 40,000 짜리를 취소하면 남은 소계 10,000 < 임계 → 기본배송비 3,000 재부과
        when(assessShippingFee.assess(any()))
                .thenReturn(new ShippingFeeAssessment(new BigDecimal("3000"), List.of()));

        CancelOrderItemsUseCase.Result result =
                service.cancelItems(77L, List.of(1L), "단순 변심", "buyer");

        assertThat(result.canceledSubtotal()).isEqualByComparingTo("40000");
        assertThat(result.additionalShippingFee()).isEqualByComparingTo("3000");
        assertThat(result.refundedAmount()).isEqualByComparingTo("37000"); // 40000 - 3000
        assertThat(order.getShippingFee()).isEqualByComparingTo("3000");   // 고객이 최종 부담하는 배송비
        verify(refundPort).refundOrderPayment(eq(77L), eq(new BigDecimal("37000")), any());
    }

    @Test
    @DisplayName("남은 주문이 여전히 무료배송 조건을 채우면 취소 금액을 그대로 환불한다")
    void noReviveWhenStillFree() {
        freeShippedOrder();
        when(assessShippingFee.assess(any())).thenReturn(ShippingFeeAssessment.none());

        CancelOrderItemsUseCase.Result result =
                service.cancelItems(77L, List.of(2L), "단순 변심", "buyer");

        assertThat(result.additionalShippingFee()).isEqualByComparingTo("0");
        assertThat(result.refundedAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("전량 취소면 이미 낸 배송비까지 돌려준다")
    void fullCancelRefundsShippingFeeToo() {
        OrderItem only = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("20000"), 1);
        only.assignId(1L);
        Order order = Order.createMultiItem(9L, List.of(only), BigDecimal.ZERO, new BigDecimal("3000"));
        order.assignId(78L);
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(78L)).thenReturn(Optional.of(order));
        when(assessShippingFee.assess(any())).thenReturn(ShippingFeeAssessment.none());

        CancelOrderItemsUseCase.Result result =
                service.cancelItems(78L, List.of(1L), "전량 취소", "buyer");

        assertThat(result.orderFullyCanceled()).isTrue();
        assertThat(result.refundedAmount()).isEqualByComparingTo("23000"); // 20000 + 3000
        assertThat(order.getShippingFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("취소한 라인만 재고가 되돌아간다 — SKU 라인은 variant, 일반 라인은 product")
    void restoresStockForCanceledLinesOnly() {
        freeShippedOrder();
        when(assessShippingFee.assess(any())).thenReturn(ShippingFeeAssessment.none());

        service.cancelItems(77L, List.of(2L), "단순 변심", "buyer");

        verify(increaseVariantStock).increase(300L, 1);
        verify(increaseProductStock, never()).increase(anyLong(), anyInt());
    }

    @Test
    @DisplayName("결제 전(CREATED) 주문은 환불을 호출하지 않는다 — 받은 돈이 없다")
    void unpaidOrderSkipsRefund() {
        OrderItem only = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("20000"), 1);
        only.assignId(1L);
        Order order = Order.createMultiItem(9L, List.of(only), BigDecimal.ZERO, BigDecimal.ZERO);
        order.assignId(79L);
        when(loadOrderPort.findById(79L)).thenReturn(Optional.of(order));
        when(assessShippingFee.assess(any())).thenReturn(ShippingFeeAssessment.none());

        CancelOrderItemsUseCase.Result result =
                service.cancelItems(79L, List.of(1L), "결제 전 취소", "buyer");

        verify(refundPort, never()).refundOrderPayment(anyLong(), any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(result.refundedAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("재부과 배송비가 취소 금액을 넘어도 환불액은 음수가 되지 않는다")
    void refundNeverNegative() {
        OrderItem cheap = OrderItem.newItem(100L, null, null, "소액상품", new BigDecimal("1000"), 1);
        OrderItem big = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("49000"), 1);
        cheap.assignId(1L);
        big.assignId(2L);
        Order order = Order.createMultiItem(9L, List.of(cheap, big), BigDecimal.ZERO, BigDecimal.ZERO);
        order.assignId(80L);
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(80L)).thenReturn(Optional.of(order));
        when(assessShippingFee.assess(any()))
                .thenReturn(new ShippingFeeAssessment(new BigDecimal("3000"), List.of()));

        CancelOrderItemsUseCase.Result result =
                service.cancelItems(80L, List.of(1L), "소액 취소", "buyer");

        assertThat(result.refundedAmount()).isEqualByComparingTo("0");
        verify(refundPort, never()).refundOrderPayment(anyLong(), any(), any());
    }

    @Test
    @DisplayName("없는 주문이면 404")
    void orderNotFound() {
        when(loadOrderPort.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelItems(999L, List.of(1L), "r", "buyer"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("부분 취소 환불은 멱등 키를 반드시 동반한다 — 재시도 이중 환불 차단")
    void refundCarriesIdempotencyKey() {
        freeShippedOrder();
        when(assessShippingFee.assess(any())).thenReturn(ShippingFeeAssessment.none());

        service.cancelItems(77L, List.of(2L), "단순 변심", "buyer");

        verify(refundPort).refundOrderPayment(eq(77L), any(), eq("order-77-items-2"));
    }

    @Test
    @DisplayName("전량 취소면 쿠폰을 되돌린다 — 환불받고 1회용 쿠폰만 잃는 비대칭 차단")
    void fullCancel_restoresCoupon() {
        freeShippedOrder();
        when(assessShippingFee.assess(any())).thenReturn(ShippingFeeAssessment.none());

        service.cancelItems(77L, List.of(1L, 2L), "전량 취소", "buyer");

        verify(couponRestorePort).restoreOnCanceled(eq(77L), any());
    }

    @Test
    @DisplayName("부분 취소는 쿠폰을 되돌리지 않는다 — 남은 라인이 여전히 그 할인을 받고 있다")
    void partialCancel_keepsCoupon() {
        freeShippedOrder();
        when(assessShippingFee.assess(any()))
                .thenReturn(new ShippingFeeAssessment(new BigDecimal("3000"), List.of()));

        service.cancelItems(77L, List.of(2L), "부분 취소", "buyer");

        verify(couponRestorePort, never()).restoreOnCanceled(anyLong(), any());
    }
}
