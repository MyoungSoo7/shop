package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 라인 단위 부분 취소.
 *
 * <p>주문 총액({@code amount})은 발행된 영수증이라 취소로 바뀌지 않는다 — 얼마를 되돌려줬는지는
 * 결제의 {@code refundedAmount} 가 들고 있고, 여기서는 "어떤 라인이 살아 있는가"만 관리한다.
 * 남은 라인은 배송비 재산정의 입력이 된다(무료배송 조건이 깨지면 배송비가 되살아난다).
 */
@DisplayName("Order — 라인 단위 부분 취소")
class OrderItemCancellationTest {

    private static Order paidOrderWithTwoLines() {
        OrderItem a = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("20000"), 1);
        OrderItem b = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("15000"), 2);
        a.assignId(1L);
        b.assignId(2L);
        Order order = Order.createMultiItem(1L, List.of(a, b), BigDecimal.ZERO, new BigDecimal("3000"));
        order.transitionTo(OrderStatus.PAID);
        return order;
    }

    @Test
    @DisplayName("취소한 라인 금액 합을 돌려주고, 남은 라인만 활성으로 남는다")
    void cancelReturnsCanceledSubtotal() {
        Order order = paidOrderWithTwoLines();

        BigDecimal canceled = order.cancelItems(List.of(2L));

        assertThat(canceled).isEqualByComparingTo("30000"); // 15000 × 2
        assertThat(order.activeItems()).extracting(OrderItem::getId).containsExactly(1L);
        assertThat(order.getAmount()).isEqualByComparingTo("53000"); // 영수증 총액은 불변
    }

    @Test
    @DisplayName("이미 취소된 라인을 다시 취소하면 거절 — 이중 환불의 입구를 막는다")
    void doubleCancelRejected() {
        Order order = paidOrderWithTwoLines();
        order.cancelItems(List.of(2L));

        assertThatThrownBy(() -> order.cancelItems(List.of(2L)))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("주문에 속하지 않은 라인은 거절")
    void foreignItemRejected() {
        Order order = paidOrderWithTwoLines();

        assertThatThrownBy(() -> order.cancelItems(List.of(99L)))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("취소할 라인을 지정하지 않으면 거절")
    void emptySelectionRejected() {
        Order order = paidOrderWithTwoLines();

        assertThatThrownBy(() -> order.cancelItems(List.of()))
                .isInstanceOf(OrderInvariantViolationException.class);
        assertThatThrownBy(() -> order.cancelItems(null))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("배송이 시작되면 부분 취소 불가 — 그 뒤는 반품 절차다")
    void afterShippingRejected() {
        Order order = paidOrderWithTwoLines();
        order.transitionTo(OrderStatus.SHIPPING_PENDING);
        order.transitionTo(OrderStatus.IN_TRANSIT);

        assertThatThrownBy(() -> order.cancelItems(List.of(1L)))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    @DisplayName("배송 준비 단계까지는 부분 취소 가능")
    void shippingPendingAllowed() {
        Order order = paidOrderWithTwoLines();
        order.transitionTo(OrderStatus.SHIPPING_PENDING);

        assertThat(order.cancelItems(List.of(1L))).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("모든 라인을 취소하면 allItemsCanceled 이 참 — 주문 종결 판정의 근거")
    void allCanceled() {
        Order order = paidOrderWithTwoLines();

        order.cancelItems(List.of(1L, 2L));

        assertThat(order.allItemsCanceled()).isTrue();
        assertThat(order.activeItems()).isEmpty();
    }

    @Test
    @DisplayName("단건(레거시) 주문은 라인이 없어 부분 취소 대상이 아니다")
    void singleItemOrderRejected() {
        Order order = Order.create(1L, 100L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);

        assertThatThrownBy(() -> order.cancelItems(List.of(1L)))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("복원된 취소 라인은 활성 목록에서 빠진다 — 재기동 후에도 같은 판정")
    void rehydratedCanceledItemStaysCanceled() {
        OrderItem canceled = OrderItem.rehydrate(9L, 1L, 100L, null, null, "상품A",
                new BigDecimal("10000"), 1, new BigDecimal("10000"),
                java.time.LocalDateTime.now(), List.of(), java.time.LocalDateTime.now());

        assertThat(canceled.isCanceled()).isTrue();
    }
}
