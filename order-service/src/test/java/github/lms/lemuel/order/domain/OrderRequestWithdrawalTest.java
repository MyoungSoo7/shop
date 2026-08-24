package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 취소·환불 <b>신청 철회</b>.
 *
 * <p>신청 상태(CANCELLATION_REQUESTED / REFUND_REQUESTED)에서 나가는 길이 승인뿐이라, 마음이
 * 바뀐 고객의 주문은 운영자가 승인할 때까지 묶여 있었다. 실무 커머스는 신청 철회를 항상 제공한다
 * (레거시의 교환·반품 신청 철회에 대응).
 *
 * <p>되돌아갈 상태는 임의로 정할 수 없다 — 그 신청을 낼 수 있었던 상태여야 한다. 즉
 * {@code restoreTo.canTransitionTo(현재 신청 상태)} 가 참인 상태만 허용된다. 이 규칙 덕에
 * "배송 중이던 주문의 환불 신청을 철회하면 배송 중으로 돌아간다"가 자동으로 성립하고,
 * 결제되지 않은 주문이 PAID 로 승격되는 일은 생기지 않는다.
 */
@DisplayName("Order — 취소·환불 신청 철회")
class OrderRequestWithdrawalTest {

    private static Order order(OrderStatus... path) {
        Order o = Order.create(1L, 2L, new BigDecimal("10000"));
        for (OrderStatus s : path) {
            o.transitionTo(s);
        }
        return o;
    }

    @Test
    @DisplayName("결제 후 취소 신청을 철회하면 PAID 로 돌아간다")
    void withdrawCancellationAfterPayment() {
        Order o = order(OrderStatus.PAID, OrderStatus.CANCELLATION_REQUESTED);

        o.withdrawRequest(OrderStatus.PAID);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 전 취소 신청을 철회하면 CREATED 로 돌아간다")
    void withdrawCancellationBeforePayment() {
        Order o = order(OrderStatus.CANCELLATION_REQUESTED);

        o.withdrawRequest(OrderStatus.CREATED);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("배송 중 환불 신청을 철회하면 배송 중으로 돌아간다 — 배송 사실은 그대로 보존")
    void withdrawRefundDuringTransit() {
        Order o = order(OrderStatus.PAID, OrderStatus.SHIPPING_PENDING,
                OrderStatus.IN_TRANSIT, OrderStatus.REFUND_REQUESTED);

        o.withdrawRequest(OrderStatus.IN_TRANSIT);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(o.isShipped()).isTrue();
    }

    @Test
    @DisplayName("신청 상태가 아니면 철회할 것이 없다")
    void nothingToWithdraw() {
        Order paid = order(OrderStatus.PAID);

        assertThatThrownBy(() -> paid.withdrawRequest(OrderStatus.CREATED))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    @DisplayName("그 신청을 낼 수 없었던 상태로는 되돌릴 수 없다 — 결제되지 않은 주문이 PAID 로 승격되지 않는다")
    void cannotRestoreToImpossibleState() {
        Order o = order(OrderStatus.PAID, OrderStatus.REFUND_REQUESTED);

        // CREATED 에서는 환불을 신청할 수 없다(전이표) → 복귀 대상이 될 수 없다
        assertThatThrownBy(() -> o.withdrawRequest(OrderStatus.CREATED))
                .isInstanceOf(OrderInvariantViolationException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
    }

    @Test
    @DisplayName("복귀 상태를 지정하지 않으면 거절 — 되돌릴 곳을 추측하지 않는다")
    void restoreTargetRequired() {
        Order o = order(OrderStatus.PAID, OrderStatus.CANCELLATION_REQUESTED);

        assertThatThrownBy(() -> o.withdrawRequest(null))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("종단 상태(CANCELED/REFUNDED)는 철회 대상이 아니다")
    void terminalNotWithdrawable() {
        Order canceled = order(OrderStatus.CANCELLATION_REQUESTED, OrderStatus.CANCELED);

        assertThatThrownBy(() -> canceled.withdrawRequest(OrderStatus.PAID))
                .isInstanceOf(InvalidOrderStateException.class);
    }
}
