package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배송비가 결제 금액에 포함되는 경로.
 *
 * <p>결제는 {@code order.amount} 로 만들어지므로(CreatePaymentUseCase), 배송비를 amount 밖에 두면
 * 고객에게 청구되지 않는다. 반대로 amount 에만 더하고 shippingFee 를 비워 두면 배송 후 환불에서
 * 배송비를 되돌려주게 된다. 둘은 같은 팩토리 호출에서 함께 정해져야 한다.
 */
@DisplayName("Order — 배송비 포함 금액 산정")
class OrderShippingFeeTest {

    private static List<OrderItem> items(String unitPrice, int qty) {
        return List.of(OrderItem.newItem(100L, null, null, "상품A", new BigDecimal(unitPrice), qty));
    }

    @Test
    @DisplayName("amount = 소계 - 할인 + 배송비, shippingFee 는 별도 보존")
    void amountIncludesShippingFee() {
        Order order = Order.createMultiItem(1L, items("10000", 3),
                new BigDecimal("2000"), new BigDecimal("3000"));

        assertThat(order.getAmount()).isEqualByComparingTo("31000"); // 30000 - 2000 + 3000
        assertThat(order.getShippingFee()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("배송비 0 이면 기존 3 인자 팩토리와 같은 금액")
    void zeroShippingMatchesLegacyFactory() {
        Order withFee = Order.createMultiItem(1L, items("10000", 2), BigDecimal.ZERO, BigDecimal.ZERO);
        Order legacy = Order.createMultiItem(1L, items("10000", 2), BigDecimal.ZERO);

        assertThat(withFee.getAmount()).isEqualByComparingTo(legacy.getAmount());
        assertThat(legacy.getShippingFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("배송비 null 은 0 으로 방어한다")
    void nullShippingFeeIsZero() {
        Order order = Order.createMultiItem(1L, items("10000", 1), BigDecimal.ZERO, null);

        assertThat(order.getAmount()).isEqualByComparingTo("10000");
        assertThat(order.getShippingFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("음수 배송비는 거절 — 배송비로 상품 대금을 깎을 수 없다")
    void negativeShippingFeeRejected() {
        assertThatThrownBy(() -> Order.createMultiItem(1L, items("10000", 1),
                BigDecimal.ZERO, new BigDecimal("-1")))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("할인이 소계 이상이면 배송비가 붙어 총액이 양수여도 거절 — 상품 대금이 0 인 주문은 없다")
    void discountStillBoundedBySubtotalNotTotal() {
        assertThatThrownBy(() -> Order.createMultiItem(1L, items("10000", 1),
                new BigDecimal("10000"), new BigDecimal("3000")))
                .isInstanceOf(OrderInvariantViolationException.class);
    }
}
