package github.lms.lemuel.order.application.service;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;

import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeOrderStatusServiceTest {

    @Mock LoadOrderPort loadOrderPort;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SaveOrderStatusHistoryPort historyPort;
    @Mock RefundOrderPaymentPort refundOrderPaymentPort;
    @Mock IncreaseProductStockUseCase increaseProductStockUseCase;
    @Mock IncreaseVariantStockUseCase increaseVariantStockUseCase;
    @Mock github.lms.lemuel.order.application.port.out.OrderPointRewardPort orderPointRewardPort;
    @Mock github.lms.lemuel.order.application.port.out.OrderCouponRestorePort orderCouponRestorePort;
    @InjectMocks ChangeOrderStatusService service;

    @Test @DisplayName("주문 취소 성공")
    void cancelOrder_success() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.cancelOrder(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(saveOrderPort).save(any());
        verify(historyPort).save(eq(1L), eq(OrderStatus.CREATED.name()),
                eq(OrderStatus.CANCELED.name()), eq("system"), eq("cancelOrder"));
    }

    @Test @DisplayName("주문 취소: 다건 주문은 라인별로 재고를 원복한다(생성 시 차감의 역연산)")
    void cancelOrder_restoresStockPerLine() {
        OrderItem skuLine = OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("10000"), 2);
        OrderItem plainLine = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("5000"), 3);
        Order order = Order.createMultiItem(1L, List.of(skuLine, plainLine));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancelOrder(1L);

        // 취소 승인(approveCancellation)·환불 승인 경로와 동일해야 한다 — 직접 취소만 재고가 새면
        // 그 수량은 영구히 판매 불가로 남는다.
        verify(increaseVariantStockUseCase).increase(500L, 2);
        verify(increaseProductStockUseCase).increase(200L, 3);
    }

    @Test @DisplayName("주문 취소: 두 번 취소해도 재고는 한 번만 원복된다(멱등)")
    void cancelOrder_doesNotRestoreTwice() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 2);
        Order order = Order.createMultiItem(1L, List.of(line));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancelOrder(1L);
        // 두 번째 취소는 도메인 전이 가드가 막지만, 설령 원복만 다시 요청돼도 늘어나선 안 된다.
        order.claimStockRestorationOnCancel();

        verify(increaseProductStockUseCase, times(1)).increase(100L, 2);
    }

    @Test @DisplayName("주문 미존재 시 예외")
    void cancelOrder_notFound() {
        when(loadOrderPort.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelOrder(999L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test @DisplayName("updateStatus: 정상 전이(CREATED→PAID)는 허용")
    void updateStatus_validTransition() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000")); // CREATED
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.updateStatus(1L, "PAID");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test @DisplayName("updateStatus: 비정상 전이(CREATED→DELIVERED)는 상태머신 가드로 차단")
    void updateStatus_invalidTransition_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000")); // CREATED
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.updateStatus(1L, "DELIVERED"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(saveOrderPort, never()).save(any());
    }

    @Test @DisplayName("환불 승인: PG 환불 실행 후 주문이 REFUNDED 로 확정되고 승인 이력 기록")
    void approveRefund_executesRefund_andConfirmsRefunded() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        // payment 가 환불 성공 시 주문을 REFUNDED 로 전이하는 부수효과를 시뮬레이션
        doAnswer(inv -> { order.transitionTo(OrderStatus.REFUNDED); return null; })
                .when(refundOrderPaymentPort).refundOrderPaymentFully(1L);

        Order result = service.approveRefund(1L, "고객 변심", "admin");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(refundOrderPaymentPort).refundOrderPaymentFully(1L);
        verify(historyPort).save(eq(1L), eq(OrderStatus.REFUND_REQUESTED.name()),
                eq(OrderStatus.REFUNDED.name()), eq("admin"), eq("고객 변심"));
    }

    @Test @DisplayName("환불 승인: 다건 주문은 라인별로 재고를 원복한다(SKU=variant, 일반=product)")
    void approveRefund_restoresStockPerLine() {
        OrderItem skuLine = OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("10000"), 2);
        OrderItem plainLine = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("5000"), 3);
        Order order = Order.createMultiItem(1L, List.of(skuLine, plainLine));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        doAnswer(inv -> { order.transitionTo(OrderStatus.REFUNDED); return null; })
                .when(refundOrderPaymentPort).refundOrderPaymentFully(1L);

        service.approveRefund(1L, "환불", "admin");

        verify(increaseVariantStockUseCase).increase(500L, 2);
        verify(increaseProductStockUseCase).increase(200L, 3);
    }

    @Test @DisplayName("환불 승인(배송 시작 후): 재고를 원복하지 않는다 — 물건이 고객 손에 있다")
    void approveRefund_afterShipping_doesNotRestoreStock() {
        OrderItem line = OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("30000"), 1);
        Order order = Order.createMultiItem(1L, List.of(line));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.SHIPPING_PENDING);
        order.transitionTo(OrderStatus.IN_TRANSIT);          // shipped = true
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approveRefund(1L, "배송후 반품", "admin");

        // 실제 회수(반품)가 확인될 때 비로소 재고로 돌아온다 — 여기서 되돌리면 장부 > 실재고.
        verifyNoInteractions(increaseVariantStockUseCase, increaseProductStockUseCase);
        assertThat(order.isStockRestored()).isFalse();
    }

    @Test @DisplayName("환불 승인(배송 시작 후): 배송비를 차감한 부분 환불 후 REFUNDED 확정")
    void approveRefund_afterShipping_deductsShippingFee() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("30000"), 1);
        Order order = Order.createMultiItem(1L, List.of(line)); // amount = 30000
        order.assignShippingFee(new BigDecimal("3000"));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.SHIPPING_PENDING);
        order.transitionTo(OrderStatus.IN_TRANSIT);          // shipped = true
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 부분 환불이므로 payment 는 주문을 자동 전이하지 않는다(mock no-op).

        Order result = service.approveRefund(1L, "단순 변심", "admin");

        // 배송비 3000 차감 → 27000 만 부분 환불 (전액 환불 경로 미사용)
        verify(refundOrderPaymentPort).refundOrderPayment(
                eq(1L), eq(new BigDecimal("27000")), eq("order-1-refund-approve"));
        verify(refundOrderPaymentPort, never()).refundOrderPaymentFully(anyLong());
        // payment 가 전이하지 못한 주문을 승인 서비스가 REFUNDED 로 확정
        assertThat(result.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test @DisplayName("환불 승인: REFUND_REQUESTED 가 아니면 PG 환불 호출 없이 차단")
    void approveRefund_invalidState_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID); // REFUND_REQUESTED 아님
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.approveRefund(1L, "사유", "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(refundOrderPaymentPort, never()).refundOrderPaymentFully(anyLong());
    }

    @Test @DisplayName("취소 승인(결제됨): 전액 환불 실행 후 REFUNDED 확정")
    void approveCancellation_paid_refundsAndBecomesRefunded() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.CANCELLATION_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundOrderPaymentPort.refundOrderPaymentFullyIfPresent(1L))
                .thenAnswer(inv -> { order.transitionTo(OrderStatus.REFUNDED); return true; });

        Order result = service.approveCancellation(1L, "취소", "admin");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(refundOrderPaymentPort).refundOrderPaymentFullyIfPresent(1L);
    }

    @Test @DisplayName("취소 승인(미결제): 환불 없이 CANCELED 확정")
    void approveCancellation_unpaid_becomesCanceled() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.CANCELLATION_REQUESTED); // CREATED → CANCELLATION_REQUESTED
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundOrderPaymentPort.refundOrderPaymentFullyIfPresent(1L)).thenReturn(false);

        Order result = service.approveCancellation(1L, "취소", "admin");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test @DisplayName("취소 승인: CANCELLATION_REQUESTED 가 아니면 차단")
    void approveCancellation_invalidState_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.approveCancellation(1L, "취소", "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(refundOrderPaymentPort, never()).refundOrderPaymentFullyIfPresent(anyLong());
    }

    @Test @DisplayName("changeShippingStatus: 단계 건너뛰기(PAID→DELIVERED)는 차단")
    void changeShippingStatus_skipStage_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.changeShippingStatus(1L, "DELIVERED", "배송완료", "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(saveOrderPort, never()).save(any());
    }

    // ───────── payment 직접 환불(PATCH /payments/{id}/refund) 경로 ─────────

    @Test @DisplayName("updateStatus(REFUNDED): 배송 전이면 재고를 원복한다")
    void updateStatus_refunded_beforeShipping_restoresStock() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 2);
        Order order = Order.createMultiItem(1L, List.of(line));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(1L, "REFUNDED");

        verify(increaseProductStockUseCase).increase(100L, 2);
    }

    @Test @DisplayName("updateStatus(PAID): 종단이 아니면 재고를 건드리지 않는다")
    void updateStatus_paid_doesNotTouchStock() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 2);
        Order order = Order.createMultiItem(1L, List.of(line));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(1L, "PAID");

        verifyNoInteractions(increaseProductStockUseCase, increaseVariantStockUseCase);
    }

    @Test @DisplayName("환불 승인 경로는 payment 의 updateStatus 와 겹쳐도 재고를 두 번 원복하지 않는다")
    void approveRefund_withPaymentDrivenTransition_restoresOnce() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 2);
        Order order = Order.createMultiItem(1L, List.of(line));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // payment 가 전액 환불로 주문을 REFUNDED 전이시키는 실제 흐름을 재현한다.
        doAnswer(inv -> { service.updateStatus(1L, "REFUNDED"); return null; })
                .when(refundOrderPaymentPort).refundOrderPaymentFully(1L);

        service.approveRefund(1L, "변심", "admin");

        verify(increaseProductStockUseCase, times(1)).increase(100L, 2);
    }

    // ───────── 미입금 만료에 따른 주문 취소 (payment 컨텍스트가 호출) ─────────

    @Test @DisplayName("미결제 주문 취소: CANCELED 전이 + 이력 + 재고 원복까지 수행하고 true")
    void cancelUnpaidOrder_cancelsAndRestoresStock() {
        OrderItem skuLine = OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("10000"), 2);
        OrderItem plainLine = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("5000"), 3);
        Order order = Order.createMultiItem(1L, List.of(skuLine, plainLine));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean cancelled = service.cancelUnpaidOrder(1L, "입금 기한 경과");

        assertThat(cancelled).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        // 주문 생성 시 차감한 재고를 되돌린다 — 만료의 존재 이유가 재고 회수다.
        verify(increaseVariantStockUseCase).increase(500L, 2);
        verify(increaseProductStockUseCase).increase(200L, 3);
        verify(historyPort).save(eq(1L), eq(OrderStatus.CREATED.name()),
                eq(OrderStatus.CANCELED.name()), eq("system"), eq("입금 기한 경과"));
    }

    @Test @DisplayName("미결제 주문 취소: 이미 결제된 주문은 손대지 않고 false")
    void cancelUnpaidOrder_paidOrder_untouched() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        boolean cancelled = service.cancelUnpaidOrder(1L, "입금 기한 경과");

        assertThat(cancelled).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(saveOrderPort, never()).save(any());
        verifyNoInteractions(increaseProductStockUseCase, increaseVariantStockUseCase);
    }

    @Test @DisplayName("미결제 주문 취소: 이미 취소된 주문은 재고를 두 번 원복하지 않는다(멱등)")
    void cancelUnpaidOrder_alreadyCanceled_idempotent() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 1);
        Order order = Order.createMultiItem(1L, List.of(line));
        order.transitionTo(OrderStatus.CANCELED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        boolean cancelled = service.cancelUnpaidOrder(1L, "입금 기한 경과");

        assertThat(cancelled).isFalse();
        verify(saveOrderPort, never()).save(any());
        verifyNoInteractions(increaseProductStockUseCase, increaseVariantStockUseCase);
    }

    @Test @DisplayName("미결제 주문 취소: 주문이 없으면 타입 예외")
    void cancelUnpaidOrder_missingOrder() {
        when(loadOrderPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelUnpaidOrder(99L, "입금 기한 경과"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("배송 완료 전이는 포인트 적립을 부른다 — 구매 확정 시점이 적립 시점이다")
    void delivered_triggersEarn() {
        github.lms.lemuel.order.domain.Order order = orderInStatus(
                github.lms.lemuel.order.domain.OrderStatus.IN_TRANSIT);
        org.mockito.Mockito.when(loadOrderPort.findById(1L))
                .thenReturn(java.util.Optional.of(order));
        org.mockito.Mockito.when(saveOrderPort.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));

        service.changeShippingStatus(1L, "DELIVERED", "배송 완료", "admin");

        org.mockito.Mockito.verify(orderPointRewardPort).earnOnDelivered(order);
        org.mockito.Mockito.verify(orderPointRewardPort, org.mockito.Mockito.never())
                .revokeOnCanceled(org.mockito.ArgumentMatchers.any());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("배송 중 전이는 적립을 부르지 않는다 — 확정 전에는 주지 않는다")
    void inTransit_doesNotEarn() {
        github.lms.lemuel.order.domain.Order order = orderInStatus(
                github.lms.lemuel.order.domain.OrderStatus.SHIPPING_PENDING);
        org.mockito.Mockito.when(loadOrderPort.findById(1L))
                .thenReturn(java.util.Optional.of(order));
        org.mockito.Mockito.when(saveOrderPort.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));

        service.changeShippingStatus(1L, "IN_TRANSIT", "출고", "admin");

        org.mockito.Mockito.verify(orderPointRewardPort, org.mockito.Mockito.never())
                .earnOnDelivered(org.mockito.ArgumentMatchers.any());
    }

    /** 지정 상태의 주문을 만든다 — 전이 규칙을 통과할 수 있는 최소 형태. */
    private static github.lms.lemuel.order.domain.Order orderInStatus(
            github.lms.lemuel.order.domain.OrderStatus status) {
        return github.lms.lemuel.order.domain.Order.rehydrate(1L, 42L, 1L,
                new java.math.BigDecimal("50000"), status,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                java.math.BigDecimal.ZERO, true);
    }
}