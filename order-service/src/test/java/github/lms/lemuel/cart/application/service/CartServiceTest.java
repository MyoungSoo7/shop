package github.lms.lemuel.cart.application.service;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock LoadCartPort loadCartPort;
    @Mock SaveCartPort saveCartPort;
    @InjectMocks CartService service;

    @Test @DisplayName("getOrCreate: 기존 장바구니가 있으면 반환")
    void getOrCreate_existing() {
        Cart cart = Cart.createEmpty(1L);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        Cart result = service.getOrCreate(1L);
        assertThat(result).isSameAs(cart);
        verify(saveCartPort, never()).save(any());
    }

    @Test @DisplayName("getOrCreate: 없으면 새로 생성")
    void getOrCreate_new() {
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.empty());
        Cart newCart = Cart.createEmpty(1L);
        when(saveCartPort.save(any())).thenReturn(newCart);
        Cart result = service.getOrCreate(1L);
        assertThat(result).isNotNull();
        verify(saveCartPort).save(any());
    }

    @Test @DisplayName("addItem: 장바구니에 상품 추가")
    void addItem() {
        Cart cart = Cart.createEmpty(1L);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        when(saveCartPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Cart result = service.addItem(1L, 10L, 20L, 2);
        assertThat(result.getItems()).hasSize(1);
    }

    @Test @DisplayName("changeQuantity: 수량 변경")
    void changeQuantity() {
        Cart cart = Cart.createEmpty(1L);
        cart.addItem(10L, 20L, 2);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        when(saveCartPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Cart result = service.changeQuantity(1L, 10L, 20L, 5);
        assertThat(result.getItems().getFirst().getQuantity()).isEqualTo(5);
    }

    @Test @DisplayName("changeQuantity: 장바구니 없으면 예외")
    void changeQuantity_empty() {
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeQuantity(1L, 10L, 20L, 5))
                .isInstanceOf(CartInvariantViolationException.class);
    }

    @Test @DisplayName("removeItem: 상품 제거")
    void removeItem() {
        Cart cart = Cart.createEmpty(1L);
        cart.addItem(10L, 20L, 2);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        when(saveCartPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Cart result = service.removeItem(1L, 10L, 20L);
        assertThat(result.getItems()).isEmpty();
    }

    @Test @DisplayName("removeItem: 장바구니 없으면 예외")
    void removeItem_empty() {
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeItem(1L, 10L, 20L))
                .isInstanceOf(CartInvariantViolationException.class);
    }

    @Test @DisplayName("clear: 장바구니 비우기")
    void clear() {
        Cart cart = Cart.createEmpty(1L);
        cart.addItem(10L, 20L, 2);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        when(saveCartPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Cart result = service.clear(1L);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test @DisplayName("clear: 장바구니 없으면 빈 장바구니 생성 후 저장")
    void clear_noCart() {
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.empty());
        when(saveCartPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Cart result = service.clear(1L);
        assertThat(result.isEmpty()).isTrue();
    }
}
