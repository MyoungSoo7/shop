package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 여러 곳 배송 묶음 id — 붙이기는 한 번, 복원도 한 번.
 *
 * <p>배송지 스냅샷과 같은 규칙을 쓴다. 다른 점은 지키려는 것이다: 스냅샷은 "처음 어디로
 * 요청했는가" 를 지키고, 이 값은 "이 주문들이 한 번의 결제에서 나왔는가" 를 지킨다.
 */
class OrderDestinationGroupTest {

    private static Order newOrder() {
        return Order.create(1L, 10L, new BigDecimal("10000"));
    }

    @Test
    @DisplayName("기본은 묶음 없음 — 배송지가 하나뿐인 보통의 주문")
    void defaultsToNull() {
        assertThat(newOrder().getDestinationGroupId()).isNull();
    }

    @Test
    @DisplayName("한 번 붙인다")
    void attachesOnce() {
        Order order = newOrder();

        order.attachDestinationGroup("group-1");

        assertThat(order.getDestinationGroupId()).isEqualTo("group-1");
    }

    @Test
    @DisplayName("다시 지정하면 거절 — 이미 만들어진 주문이 다른 결제의 묶음으로 옮겨 가지 않게")
    void rejectsReassignment() {
        Order order = newOrder();
        order.attachDestinationGroup("group-1");

        assertThatThrownBy(() -> order.attachDestinationGroup("group-2"))
                .isInstanceOf(OrderInvariantViolationException.class);
        assertThat(order.getDestinationGroupId()).isEqualTo("group-1");
    }

    @Test
    @DisplayName("null 은 무시 — 묶음이 아닌 주문의 복원 경로가 그대로 지나간다")
    void ignoresNull() {
        Order order = newOrder();

        order.attachDestinationGroup(null);
        order.attachDestinationGroup("group-1");
        order.attachDestinationGroup(null);

        assertThat(order.getDestinationGroupId()).isEqualTo("group-1");
    }
}
