package github.lms.lemuel.cart.domain;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CartItemTest {

    @Test @DisplayName("newItem: 유효한 파라미터로 생성한다")
    void newItem_valid() {
        CartItem item = CartItem.newItem(1L, 2L, 3);
        assertThat(item.getProductId()).isEqualTo(1L);
        assertThat(item.getVariantId()).isEqualTo(2L);
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getAddedAt()).isNotNull();
        assertThat(item.getId()).isNull();
        assertThat(item.getCartId()).isNull();
    }

    @Test @DisplayName("newItem: null productId이면 NPE")
    void newItem_nullProductId() {
        assertThatThrownBy(() -> CartItem.newItem(null, 1L, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test @DisplayName("newItem: 수량 0이면 예외")
    void newItem_zeroQuantity() {
        assertThatThrownBy(() -> CartItem.newItem(1L, 1L, 0))
                .isInstanceOf(CartInvariantViolationException.class)
                .hasMessage("quantity 는 양수여야 합니다");
    }

    @Test @DisplayName("newItem: 음수 수량이면 예외")
    void newItem_negativeQuantity() {
        assertThatThrownBy(() -> CartItem.newItem(1L, 1L, -1))
                .isInstanceOf(CartInvariantViolationException.class);
    }

    @Test @DisplayName("newItem: variantId null 허용")
    void newItem_nullVariantId() {
        CartItem item = CartItem.newItem(1L, null, 1);
        assertThat(item.getVariantId()).isNull();
    }

    @Test @DisplayName("rehydrate: 모든 필드가 복원된다")
    void rehydrate() {
        var now = java.time.LocalDateTime.of(2025, 1, 1, 0, 0);
        CartItem item = CartItem.rehydrate(10L, 20L, 30L, 40L, 5, now);
        assertThat(item.getId()).isEqualTo(10L);
        assertThat(item.getCartId()).isEqualTo(20L);
        assertThat(item.getProductId()).isEqualTo(30L);
        assertThat(item.getVariantId()).isEqualTo(40L);
        assertThat(item.getQuantity()).isEqualTo(5);
        assertThat(item.getAddedAt()).isEqualTo(now);
    }

    @Test @DisplayName("assignId: 한 번만 부여 가능")
    void assignId_once() {
        CartItem item = CartItem.newItem(1L, 1L, 1);
        item.assignId(99L);
        assertThat(item.getId()).isEqualTo(99L);
        assertThatThrownBy(() -> item.assignId(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("id 1회만 부여");
    }
}
