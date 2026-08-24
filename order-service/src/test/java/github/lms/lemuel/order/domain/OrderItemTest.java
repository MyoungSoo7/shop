package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemTest {

    @Test
    @DisplayName("newItem: lineAmount 는 unitPrice * quantity 로 자동 계산")
    void lineAmount_autoComputed() {
        OrderItem item = OrderItem.newItem(1L, null, null, "맥북", new BigDecimal("3000000"), 2);

        assertThat(item.getLineAmount()).isEqualByComparingTo("6000000");
        assertThat(item.getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("newItem: SKU 라인 — variantId / sku 모두 채워짐")
    void newItem_withSku() {
        OrderItem item = OrderItem.newItem(1L, 99L, "SKU-RED-L", "티셔츠", new BigDecimal("19000"), 3);

        assertThat(item.getVariantId()).isEqualTo(99L);
        assertThat(item.getSku()).isEqualTo("SKU-RED-L");
        assertThat(item.getLineAmount()).isEqualByComparingTo("57000");
    }

    @Test
    @DisplayName("validation: 음수 수량 / 음수 가격 / 빈 상품명")
    void validation() {
        assertThatThrownBy(() -> OrderItem.newItem(1L, null, null, "P", BigDecimal.TEN, 0))
                .isInstanceOf(OrderInvariantViolationException.class);
        assertThatThrownBy(() -> OrderItem.newItem(1L, null, null, "P", new BigDecimal("-1"), 1))
                .isInstanceOf(OrderInvariantViolationException.class);
        assertThatThrownBy(() -> OrderItem.newItem(1L, null, null, "", BigDecimal.TEN, 1))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("attachToOrder: 한 번 부여된 orderId 와 다른 값으로 재호출 시 IllegalStateException")
    void attachToOrder_immutableAfterFirstAttach() {
        OrderItem item = OrderItem.newItem(1L, null, null, "P", BigDecimal.TEN, 1);
        item.assignId(10L);
        // 첫 번째 attach 는 OK
        assertThat(item.getOrderId()).isNull();
    }
}
