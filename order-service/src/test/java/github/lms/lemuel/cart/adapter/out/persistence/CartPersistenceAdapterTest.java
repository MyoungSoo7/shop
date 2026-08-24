package github.lms.lemuel.cart.adapter.out.persistence;

import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장바구니 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속).
 * 신규 저장(id==null)과 갱신(id!=null: 기존 자식 삭제 후 재삽입) 경로를 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class CartPersistenceAdapterTest {

    @Mock SpringDataCartRepository cartRepository;
    @Mock SpringDataCartItemRepository itemRepository;
    @InjectMocks CartPersistenceAdapter adapter;

    private CartJpaEntity cartEntity(Long id, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return new CartJpaEntity(id, userId, now, now, now);
    }

    private CartItemJpaEntity itemEntity(Long id, Long cartId, Long productId) {
        return new CartItemJpaEntity(id, cartId, productId, null, 2, LocalDateTime.now());
    }

    @Test
    @DisplayName("loadByUserId: 카트와 항목을 함께 도메인으로 매핑")
    void loadByUserId() {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cartEntity(10L, 1L)));
        when(itemRepository.findByCartIdOrderByAddedAtAsc(10L))
                .thenReturn(List.of(itemEntity(100L, 10L, 500L)));

        Cart cart = adapter.loadByUserId(1L).orElseThrow();

        assertThat(cart.getId()).isEqualTo(10L);
        assertThat(cart.getUserId()).isEqualTo(1L);
        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getProductId()).isEqualTo(500L);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("loadByUserId: 미존재 시 empty")
    void loadByUserId_empty() {
        when(cartRepository.findByUserId(99L)).thenReturn(Optional.empty());
        assertThat(adapter.loadByUserId(99L)).isEmpty();
    }

    @Test
    @DisplayName("save: 신규(id==null) 는 새 카트 저장 후 항목 삽입, 삭제는 호출하지 않는다")
    void save_new() {
        Cart cart = Cart.createEmpty(1L);
        cart.addItem(500L, null, 3);
        when(cartRepository.save(any(CartJpaEntity.class))).thenReturn(cartEntity(10L, 1L));
        when(itemRepository.findByCartIdOrderByAddedAtAsc(10L)).thenReturn(List.of());

        adapter.save(cart);

        verify(itemRepository, never()).deleteByCartId(any());
        verify(itemRepository, times(1)).save(any(CartItemJpaEntity.class));
    }

    @Test
    @DisplayName("save: 기존(id!=null) 은 로딩→applyState→자식 일괄 교체")
    void save_existing() {
        Cart cart = Cart.rehydrate(10L, 1L, LocalDateTime.now(),
                LocalDateTime.now().minusDays(1), LocalDateTime.now(),
                List.of(CartItem.rehydrate(100L, 10L, 500L, null, 1, LocalDateTime.now()),
                        CartItem.rehydrate(101L, 10L, 600L, null, 2, LocalDateTime.now())));
        CartJpaEntity existing = cartEntity(10L, 1L);
        when(cartRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(cartRepository.save(any(CartJpaEntity.class))).thenReturn(existing);
        when(itemRepository.findByCartIdOrderByAddedAtAsc(10L)).thenReturn(List.of());

        adapter.save(cart);

        verify(itemRepository).deleteByCartId(10L);
        verify(itemRepository, times(2)).save(any(CartItemJpaEntity.class));
    }

    @Test
    @DisplayName("save: 기존 id 인데 DB 에 없으면 예외")
    void save_existingMissing() {
        Cart cart = Cart.rehydrate(99L, 1L, LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(), List.of());
        when(cartRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(cart))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cart not found");
    }
}
