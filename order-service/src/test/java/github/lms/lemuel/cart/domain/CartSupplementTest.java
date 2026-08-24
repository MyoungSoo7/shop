package github.lms.lemuel.cart.domain;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cart — 식별자 부여/자식 배선/재수화 보조 커버리지")
class CartSupplementTest {

    @Test
    @DisplayName("assignId — 1회만, 재부여 예외")
    void assignId() {
        Cart cart = Cart.createEmpty(10L);
        assertThat(cart.getId()).isNull();
        cart.assignId(5L);
        assertThat(cart.getId()).isEqualTo(5L);
        assertThatThrownBy(() -> cart.assignId(6L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("attachItemsToCart — id 없으면 예외, 있으면 자식 배선")
    void attachItemsToCart() {
        Cart cart = Cart.createEmpty(10L);
        cart.addItem(1L, 2L, 1);
        assertThatThrownBy(cart::attachItemsToCart).isInstanceOf(IllegalStateException.class);
        cart.assignId(99L);
        cart.attachItemsToCart();
        assertThat(cart.getItems().get(0).getCartId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("replaceItems — null 은 비우고, 목록은 교체")
    void replaceItems() {
        Cart cart = Cart.createEmpty(10L);
        cart.addItem(1L, null, 1);
        cart.replaceItems(null);
        assertThat(cart.getItems()).isEmpty();

        CartItem reloaded = CartItem.rehydrate(1L, 10L, 3L, 4L, 2, LocalDateTime.now());
        cart.replaceItems(List.of(reloaded));
        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getProductId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("rehydrate — 접근자 및 생성/수정 시각 보존")
    void rehydrate() {
        LocalDateTime now = LocalDateTime.now();
        CartItem item = CartItem.rehydrate(1L, 7L, 3L, 4L, 5, now);
        Cart cart = Cart.rehydrate(7L, 10L, now, now, now, List.of(item));
        assertThat(cart.getId()).isEqualTo(7L);
        assertThat(cart.getCreatedAt()).isEqualTo(now);
        assertThat(cart.getUpdatedAt()).isEqualTo(now);
        assertThat(cart.totalQuantity()).isEqualTo(5);
    }
}
