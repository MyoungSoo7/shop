package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class OrderStatusTest {

    @Test @DisplayName("모든 주문 상태값 존재") void allStatuses() {
        assertThat(OrderStatus.values()).contains(
                OrderStatus.CREATED, OrderStatus.PAID,
                OrderStatus.CANCELED, OrderStatus.REFUNDED
        );
    }

    @Test @DisplayName("정상 전이 허용: CREATED→PAID→SHIPPING_PENDING→IN_TRANSIT→DELIVERED")
    void validHappyPath() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPING_PENDING)).isTrue();
        assertThat(OrderStatus.SHIPPING_PENDING.canTransitionTo(OrderStatus.IN_TRANSIT)).isTrue();
        assertThat(OrderStatus.IN_TRANSIT.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @Test @DisplayName("환불은 결제 이후 어떤 단계(배송 포함)에서도 허용")
    void refundFromAnyPostPaidStage() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
        assertThat(OrderStatus.SHIPPING_PENDING.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
        assertThat(OrderStatus.IN_TRANSIT.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
    }

    @Test @DisplayName("비정상 전이 차단: CREATED→DELIVERED, CREATED→REFUNDED, DELIVERED→PAID")
    void invalidTransitionsBlocked() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.REFUNDED)).isFalse();
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }

    @Test @DisplayName("종단 상태는 어떤 전이도 불가")
    void terminalStates() {
        assertThat(OrderStatus.CANCELED.isTerminal()).isTrue();
        assertThat(OrderStatus.REFUNDED.isTerminal()).isTrue();
        assertThat(OrderStatus.REFUND_COMPLETED.isTerminal()).isTrue();
        assertThat(OrderStatus.REFUNDED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }
}
