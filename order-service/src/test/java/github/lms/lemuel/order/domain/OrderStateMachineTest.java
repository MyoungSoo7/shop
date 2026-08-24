package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Order — 상태머신 전이 및 접근자 커버리지")
class OrderStateMachineTest {

    private Order paidOrder() {
        Order o = Order.create(1L, 100L, new BigDecimal("10000"));
        o.complete();
        return o;
    }

    @Test
    @DisplayName("legacy create(userId, amount) — 기본 productId=1")
    void legacyCreate() {
        Order o = Order.create(7L, new BigDecimal("5000"));
        assertThat(o.getProductId()).isEqualTo(1L);
        assertThat(o.getUserId()).isEqualTo(7L);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("rehydrate 복원 팩토리 — null status/시각 기본값")
    void fullConstructor() {
        Order o = Order.rehydrate(5L, 6L, 7L, new BigDecimal("100"), null, null, null, null, false);
        assertThat(o.getId()).isEqualTo(5L);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(o.getCreatedAt()).isNotNull();
        assertThat(o.getUpdatedAt()).isNotNull();

        Order o2 = Order.rehydrate(1L, 2L, 3L, BigDecimal.ONE, OrderStatus.PAID,
                LocalDateTime.now(), LocalDateTime.now(), null, false);
        assertThat(o2.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("transitionTo — PAID→SHIPPING_PENDING→IN_TRANSIT→DELIVERED, shipped 플래그")
    void transition_shippingPath() {
        Order o = paidOrder();
        o.transitionTo(OrderStatus.SHIPPING_PENDING);
        o.transitionTo(OrderStatus.IN_TRANSIT);
        assertThat(o.isShipped()).isTrue();
        o.transitionTo(OrderStatus.DELIVERED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        o.transitionTo(OrderStatus.REFUNDED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("transitionTo — 동일 상태 재적용은 멱등 no-op")
    void transition_idempotent() {
        Order o = paidOrder();
        o.transitionTo(OrderStatus.PAID);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("transitionTo — null 대상 예외")
    void transition_null() {
        assertThatThrownBy(() -> paidOrder().transitionTo(null))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("transitionTo — 허용되지 않은 전이 예외 (CREATED→DELIVERED)")
    void transition_illegal() {
        Order o = Order.create(1L, 2L, BigDecimal.TEN);
        assertThatThrownBy(() -> o.transitionTo(OrderStatus.DELIVERED))
                .isInstanceOfSatisfying(InvalidOrderStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(OrderStatus.CREATED);
                    assertThat(ex.getTo()).isEqualTo(OrderStatus.DELIVERED);
                });
    }

    @Test
    @DisplayName("cancel — CREATED 만 취소, 그 외 예외")
    void cancel() {
        Order o = Order.create(1L, 2L, BigDecimal.TEN);
        assertThat(o.isCancelable()).isTrue();
        o.cancel();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThatThrownBy(() -> paidOrder().cancel()).isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    @DisplayName("complete / refund — 상태 가드")
    void completeAndRefund() {
        Order o = paidOrder();
        assertThat(o.isRefundable()).isTrue();
        o.refund();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThatThrownBy(o::complete).isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(o::refund).isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    @DisplayName("shippingFee 세터 null 방어, shipped 세터")
    void shippingAccessors() {
        Order o = Order.create(1L, 2L, BigDecimal.TEN);
        o.assignShippingFee(null);
        assertThat(o.getShippingFee()).isEqualByComparingTo("0");
        o.assignShippingFee(new BigDecimal("3000"));
        assertThat(o.getShippingFee()).isEqualByComparingTo("3000");
        Order shipped = Order.rehydrate(9L, 1L, 2L, BigDecimal.TEN, OrderStatus.PAID,
                LocalDateTime.now(), LocalDateTime.now(), BigDecimal.ZERO, true);
        assertThat(shipped.isShipped()).isTrue();
        assertThat(shipped.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("다건 주문 — items 채우고 attach/replace")
    void multiItem() {
        OrderItem item = OrderItem.newItem(100L, 200L, "SKU-1", "상품", new BigDecimal("5000"), 2);
        Order o = Order.createMultiItem(9L, java.util.List.of(item));
        assertThat(o.isMultiItem()).isTrue();
        assertThat(o.getAmount()).isEqualByComparingTo("10000");
        assertThatThrownBy(o::attachItemsToOrder).isInstanceOf(IllegalStateException.class);
        o.assignId(50L);
        o.attachItemsToOrder();
        o.replaceItems(null);
        assertThat(o.getItems()).isEmpty();
    }

    @Test
    @DisplayName("createMultiItem — 빈 목록/과다 할인 예외")
    void multiItem_guards() {
        assertThatThrownBy(() -> Order.createMultiItem(1L, java.util.List.of()))
                .isInstanceOf(OrderInvariantViolationException.class);
        OrderItem item = OrderItem.newItem(1L, 1L, "SKU-2", "상품2", new BigDecimal("1000"), 1);
        assertThatThrownBy(() -> Order.createMultiItem(1L, java.util.List.of(item), new BigDecimal("-1")))
                .isInstanceOf(OrderInvariantViolationException.class);
        assertThatThrownBy(() -> Order.createMultiItem(1L, java.util.List.of(item), new BigDecimal("1000")))
                .isInstanceOf(OrderInvariantViolationException.class);
    }
}
