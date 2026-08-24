package github.lms.lemuel.cart.domain;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    @Test
    @DisplayName("createEmpty: 비어있는 장바구니 생성")
    void createEmpty() {
        Cart cart = Cart.createEmpty(100L);

        assertThat(cart.getUserId()).isEqualTo(100L);
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.totalQuantity()).isZero();
    }

    @Test
    @DisplayName("addItem: 새 (productId, variantId) → 새 라인 추가")
    void addItem_newLine() {
        Cart cart = Cart.createEmpty(100L);

        cart.addItem(1L, null, 2);
        cart.addItem(2L, 99L, 3);

        assertThat(cart.getItems()).hasSize(2);
        assertThat(cart.totalQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("addItem: 같은 (productId, variantId) → 수량 증가로 자동 변환")
    void addItem_sameSku_increasesQuantity() {
        Cart cart = Cart.createEmpty(100L);

        cart.addItem(1L, 99L, 2);
        cart.addItem(1L, 99L, 3);  // 같은 SKU 재추가

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("addItem: variantId 가 다르면 별도 라인 (옵션이 다른 같은 상품)")
    void addItem_differentVariantSeparateLine() {
        Cart cart = Cart.createEmpty(100L);

        cart.addItem(1L, 99L, 1); // 빨강
        cart.addItem(1L, 100L, 1); // 파랑

        assertThat(cart.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("changeQuantity: 0 으로 변경 → removeItem 으로 자동 변환")
    void changeQuantity_zero_removes() {
        Cart cart = Cart.createEmpty(100L);
        cart.addItem(1L, null, 5);

        cart.changeQuantity(1L, null, 0);

        assertThat(cart.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("changeQuantity: 음수 → IllegalArgumentException")
    void changeQuantity_negative() {
        Cart cart = Cart.createEmpty(100L);
        cart.addItem(1L, null, 5);

        assertThatThrownBy(() -> cart.changeQuantity(1L, null, -1))
                .isInstanceOf(CartInvariantViolationException.class);
    }

    @Test
    @DisplayName("changeQuantity: 존재하지 않는 항목 → IllegalArgumentException")
    void changeQuantity_unknown() {
        Cart cart = Cart.createEmpty(100L);

        assertThatThrownBy(() -> cart.changeQuantity(999L, null, 5))
                .isInstanceOf(CartInvariantViolationException.class);
    }

    @Test
    @DisplayName("removeItem: 매칭되는 라인만 제거")
    void removeItem() {
        Cart cart = Cart.createEmpty(100L);
        cart.addItem(1L, null, 1);
        cart.addItem(2L, 99L, 1);

        cart.removeItem(1L, null);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getProductId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("clear: 모든 항목 제거")
    void clear() {
        Cart cart = Cart.createEmpty(100L);
        cart.addItem(1L, null, 1);
        cart.addItem(2L, null, 1);

        cart.clear();

        assertThat(cart.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("addItem 후 lastActiveAt 갱신")
    void touch_updatesLastActive() throws InterruptedException {
        Cart cart = Cart.createEmpty(100L);
        var before = cart.getLastActiveAt();

        Thread.sleep(2);
        cart.addItem(1L, null, 1);

        assertThat(cart.getLastActiveAt()).isAfter(before);
    }
}
