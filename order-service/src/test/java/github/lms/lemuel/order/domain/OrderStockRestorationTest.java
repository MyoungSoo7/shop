package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 원복 권한 판정 — 취소·환불로 종단에 도달한 주문의 재고를 되돌릴지 도메인이 정한다.
 *
 * <p>두 축이 있다.
 * <ul>
 *   <li><b>배송 여부</b>: 배송이 시작된 주문은 물건이 고객 손에 있어 환불만으로 재고를 되돌리면
 *       장부재고가 실재고를 넘어 초과판매가 난다. 실제 회수(반품)가 확인될 때만 되돌린다.</li>
 *   <li><b>멱등</b>: 같은 주문이 두 번 원복되면 없는 재고가 생긴다. 원복 권한은 딱 한 번만 나간다.</li>
 * </ul>
 */
class OrderStockRestorationTest {

    private Order multiItemOrder() {
        OrderItem skuLine = OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("10000"), 2);
        OrderItem plainLine = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("5000"), 3);
        return Order.createMultiItem(1L, List.of(skuLine, plainLine));
    }

    private Order shippedOrder() {
        Order order = multiItemOrder();
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.SHIPPING_PENDING);
        order.transitionTo(OrderStatus.IN_TRANSIT);   // shipped = true
        return order;
    }

    // ───────── 취소·환불에 따른 원복 ─────────

    @Test @DisplayName("배송 전 주문은 전 라인을 원복 대상으로 넘긴다")
    void beforeShipping_claimsAllLines() {
        Order order = multiItemOrder();

        List<OrderItem> claimed = order.claimStockRestorationOnCancel();

        assertThat(claimed).hasSize(2);
        assertThat(order.isStockRestored()).isTrue();
    }

    @Test @DisplayName("배송이 시작된 주문은 원복하지 않는다 — 물건이 고객 손에 있다")
    void afterShipping_claimsNothing() {
        Order order = shippedOrder();

        List<OrderItem> claimed = order.claimStockRestorationOnCancel();

        assertThat(claimed).isEmpty();
        // 원복하지 않았으므로 나중에 반품 회수가 확인되면 그때 원복할 수 있어야 한다.
        assertThat(order.isStockRestored()).isFalse();
    }

    @Test @DisplayName("원복 권한은 한 번만 나간다 — 두 번째 요청은 빈 목록")
    void claimIsIdempotent() {
        Order order = multiItemOrder();

        assertThat(order.claimStockRestorationOnCancel()).hasSize(2);
        assertThat(order.claimStockRestorationOnCancel()).isEmpty();
        assertThat(order.claimStockRestorationOnCancel()).isEmpty();
    }

    @Test @DisplayName("단건 레거시 주문은 라인이 없어 원복 대상도 없다")
    void legacySingleOrder_noLines() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));

        assertThat(order.claimStockRestorationOnCancel()).isEmpty();
    }

    // ───────── 반품 회수에 따른 원복 ─────────

    @Test @DisplayName("반품 회수는 배송된 주문도 원복한다 — 물건이 실제로 돌아왔다")
    void onReturn_claimsEvenWhenShipped() {
        Order order = shippedOrder();

        List<OrderItem> claimed = order.claimStockRestorationOnReturn();

        assertThat(claimed).hasSize(2);
        assertThat(order.isStockRestored()).isTrue();
    }

    @Test @DisplayName("배송 전 환불로 이미 원복된 주문은 반품 회수로 다시 원복되지 않는다")
    void onReturn_afterCancelRestore_isNoop() {
        Order order = multiItemOrder();
        order.claimStockRestorationOnCancel();   // 배송 전 취소로 원복 완료

        assertThat(order.claimStockRestorationOnReturn()).isEmpty();
    }

    @Test @DisplayName("반품 회수 원복도 한 번만 나간다")
    void onReturn_isIdempotent() {
        Order order = shippedOrder();

        assertThat(order.claimStockRestorationOnReturn()).hasSize(2);
        assertThat(order.claimStockRestorationOnReturn()).isEmpty();
    }

    // ───────── 회수 대기 판정 (관리자 조회) ─────────

    @Test @DisplayName("배송 후 환불로 원복이 보류된 주문은 회수 대기다")
    void shippedAndRefunded_isAwaitingReclaim() {
        Order order = shippedOrder();
        order.transitionTo(OrderStatus.REFUNDED);
        order.claimStockRestorationOnCancel();   // 배송됨 → 보류

        assertThat(order.isAwaitingStockReclaim()).isTrue();
    }

    @Test @DisplayName("배송 전 취소로 이미 원복된 주문은 회수 대기가 아니다")
    void restoredOrder_isNotAwaiting() {
        Order order = multiItemOrder();
        order.claimStockRestorationOnCancel();   // 원복 완료

        assertThat(order.isAwaitingStockReclaim()).isFalse();
    }

    @Test @DisplayName("아직 종단에 도달하지 않은 배송 중 주문은 회수 대기가 아니다")
    void inTransitOrder_isNotAwaiting() {
        Order order = shippedOrder();            // IN_TRANSIT — 환불도 취소도 아님

        assertThat(order.isAwaitingStockReclaim()).isFalse();
    }

    @Test @DisplayName("배송된 적 없는 주문은 회수할 물건이 없다")
    void neverShipped_isNotAwaiting() {
        Order order = multiItemOrder();
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.REFUNDED);

        assertThat(order.isAwaitingStockReclaim()).isFalse();
    }

    @Test @DisplayName("라인이 없는 단건 레거시 주문은 회수 대기 대상이 아니다")
    void legacyOrder_isNotAwaiting() {
        Order order = Order.rehydrate(9L, 1L, 1L, new BigDecimal("10000"),
                OrderStatus.REFUNDED, null, null, BigDecimal.ZERO, true, false);

        assertThat(order.isAwaitingStockReclaim()).isFalse();
    }

    // ───────── 복원(rehydrate) 경계 ─────────

    @Test @DisplayName("이미 원복된 것으로 복원된 주문은 다시 원복되지 않는다(재기동 후에도 멱등)")
    void rehydratedAsRestored_claimsNothing() {
        Order order = Order.rehydrate(7L, 1L, null, new BigDecimal("35000"),
                OrderStatus.REFUNDED, null, null, BigDecimal.ZERO, false, true);
        order.replaceItems(List.of(
                OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 2)));

        assertThat(order.claimStockRestorationOnCancel()).isEmpty();
        assertThat(order.claimStockRestorationOnReturn()).isEmpty();
    }
}
