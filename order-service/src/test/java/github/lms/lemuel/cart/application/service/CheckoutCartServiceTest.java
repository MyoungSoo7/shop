package github.lms.lemuel.cart.application.service;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutCartServiceTest {

    @Mock LoadCartPort loadCartPort;
    @Mock SaveCartPort saveCartPort;
    @Mock CreateMultiItemOrderUseCase createOrderUseCase;
    @InjectMocks CheckoutCartService service;

    @Test @DisplayName("checkout: 장바구니 → 주문 변환 성공")
    void checkout_success() {
        Cart cart = Cart.createEmpty(1L);
        cart.addItem(10L, 20L, 2);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        Order mockOrder = mock(Order.class);
        when(mockOrder.getId()).thenReturn(100L);
        when(createOrderUseCase.create(eq(1L), any())).thenReturn(mockOrder);
        when(saveCartPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.checkout(1L);
        assertThat(result).isSameAs(mockOrder);
        verify(saveCartPort).save(any());
    }

    @Test @DisplayName("checkout: 장바구니 없으면 예외")
    void checkout_noCart() {
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkout(1L))
                .isInstanceOf(CartInvariantViolationException.class)
                .hasMessage("장바구니가 없습니다");
    }

    @Test @DisplayName("checkout: 빈 장바구니면 예외")
    void checkout_emptyCart() {
        Cart cart = Cart.createEmpty(1L);
        when(loadCartPort.loadByUserId(1L)).thenReturn(Optional.of(cart));
        assertThatThrownBy(() -> service.checkout(1L))
                .isInstanceOf(CartInvariantViolationException.class)
                .hasMessage("장바구니가 비어있습니다");
    }
}
