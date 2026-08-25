package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 할인의 라인 안분 — {@code Order.createMultiItem} 이 결제 금액을 확정하는 자리에서 한 번 배분한다.
 *
 * <p>지키는 것은 두 가지다.
 * <ul>
 *   <li><b>Σ 안분액 = 할인액</b> — 한 푼도 새거나 남지 않는다. 어긋나면 라인을 차례로 취소한
 *       환불 합계가 결제액과 달라지고, 넘치는 쪽이면 마지막 취소가 PG 에서 거절된다.</li>
 *   <li><b>라인별 안분액 ≤ 라인 금액</b> — 순액이 음수인 라인은 "취소하면 고객이 돈을 낸다"는 뜻이다.</li>
 * </ul>
 */
class OrderDiscountAllocationTest {

    private static Order order(BigDecimal discount, String... unitPrices) {
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < unitPrices.length; i++) {
            items.add(OrderItem.newItem((long) (i + 1), null, null,
                    "상품" + i, new BigDecimal(unitPrices[i]), 1));
        }
        return Order.createMultiItem(9L, items, discount, BigDecimal.ZERO);
    }

    private static BigDecimal allocatedSum(Order order) {
        return order.getItems().stream()
                .map(OrderItem::getAllocatedDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("금액 비례로 배분한다 — 큰 라인이 큰 몫을 진다")
    void allocatesProportionally() {
        Order order = order(new BigDecimal("30000"), "90000", "10000");

        assertThat(order.getItems().get(0).getAllocatedDiscount()).isEqualByComparingTo("27000");
        assertThat(order.getItems().get(1).getAllocatedDiscount()).isEqualByComparingTo("3000");
        assertThat(order.getItems().get(0).getNetAmount()).isEqualByComparingTo("63000");
        assertThat(order.getItems().get(1).getNetAmount()).isEqualByComparingTo("7000");
    }

    @Test
    @DisplayName("쿠폰 없는 주문은 모든 라인의 몫이 0 이고 순액 = 정가")
    void noDiscount_allocatesZero() {
        Order order = order(BigDecimal.ZERO, "40000", "10000");

        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.getAllocatedDiscount()).isEqualByComparingTo("0");
            assertThat(item.getNetAmount()).isEqualByComparingTo(item.getLineAmount());
        });
    }

    @Test
    @DisplayName("나누어떨어지지 않아도 합은 정확히 할인액이다 — 버린 잔돈이 사라지지 않는다")
    void remainderIsNotLost() {
        // 1,000 을 3 등분: 각 333.33… → 내림 333 씩이면 합 999, 1 원이 뜬다.
        Order order = order(new BigDecimal("1000"), "10000", "10000", "10000");

        assertThat(allocatedSum(order)).isEqualByComparingTo("1000");
        assertThat(order.getItems()).extracting(OrderItem::getAllocatedDiscount)
                .anySatisfy(share -> assertThat(share).isEqualByComparingTo("334"));
    }

    @Test
    @DisplayName("소액 라인이 여러 개여도 어떤 라인도 자기 금액보다 많이 할인받지 않는다")
    void neverAllocatesMoreThanLineAmount() {
        // 1 원짜리 5 개(소계 5)에 할인 4. 비례 몫은 라인당 0.8 → 전부 내림 0, 잔돈이 4 나온다.
        // 이 잔돈을 한 라인에 몰면 그 라인은 1 원짜리인데 4 원을 할인받아 순액 -3 이 된다.
        Order order = order(new BigDecimal("4"), "1", "1", "1", "1", "1");

        assertThat(allocatedSum(order)).isEqualByComparingTo("4");
        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.getAllocatedDiscount()).isLessThanOrEqualTo(item.getLineAmount());
            assertThat(item.getNetAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("수량이 있는 라인은 수량까지 반영된 라인 금액 기준으로 배분된다")
    void allocatesOnLineAmountNotUnitPrice() {
        OrderItem two = OrderItem.newItem(1L, null, null, "A", new BigDecimal("10000"), 2);
        OrderItem one = OrderItem.newItem(2L, null, null, "B", new BigDecimal("10000"), 1);
        Order order = Order.createMultiItem(9L, List.of(two, one),
                new BigDecimal("3000"), BigDecimal.ZERO);

        // 소계 30,000 중 20,000 : 10,000 → 2,000 : 1,000
        assertThat(two.getAllocatedDiscount()).isEqualByComparingTo("2000");
        assertThat(one.getAllocatedDiscount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("cancelItems 는 정가가 아니라 그 라인의 실지불액 합을 돌려준다")
    void cancelItemsReturnsNetAmount() {
        Order order = order(new BigDecimal("20000"), "50000", "50000");
        order.getItems().get(0).assignId(1L);
        order.getItems().get(1).assignId(2L);
        order.transitionTo(OrderStatus.PAID);

        BigDecimal canceled = order.cancelItems(List.of(1L));

        assertThat(canceled).isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("라인을 전부 차례로 취소하면 실지불액 합계가 결제 금액과 정확히 같다")
    void sequentialCancelsSumToPaidAmount() {
        // 나누어떨어지지 않는 배분에서도 성립해야 한다 — 여기서 어긋나면 마지막 취소가 PG 에서 거절된다.
        Order order = order(new BigDecimal("7777"), "33333", "10000", "1");
        order.getItems().get(0).assignId(1L);
        order.getItems().get(1).assignId(2L);
        order.getItems().get(2).assignId(3L);
        order.transitionTo(OrderStatus.PAID);

        BigDecimal total = order.cancelItems(List.of(1L))
                .add(order.cancelItems(List.of(2L)))
                .add(order.cancelItems(List.of(3L)));

        assertThat(total).isEqualByComparingTo(order.getAmount()); // 배송비 0 이라 amount = 소계 - 할인
    }
}
