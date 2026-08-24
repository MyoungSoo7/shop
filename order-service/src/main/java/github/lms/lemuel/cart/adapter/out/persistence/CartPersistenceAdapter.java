package github.lms.lemuel.cart.adapter.out.persistence;

import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 장바구니 영속성 어댑터 (JPA/PostgreSQL).
 *
 * <p>저장 전략: 단순 "기존 자식 삭제 → 현재 상태 일괄 INSERT". 항목 수가 적은 장바구니에는
 * 충분하며, "어떤 라인이 변경됐는지" 까지 추적할 필요가 없다.
 *
 * <p>{@code cart.store=jpa} (기본값) 일 때만 활성화된다. {@code cart.store=redis} 로 바꾸면
 * {@link RedisCartAdapter} 가 대신 활성화되고 application 코드는 변경 없이 동작한다 — 헥사고날 경계의 가치.
 */
@Component
@ConditionalOnProperty(name = "cart.store", havingValue = "jpa", matchIfMissing = true)
public class CartPersistenceAdapter implements LoadCartPort, SaveCartPort {

    private final SpringDataCartRepository cartRepository;
    private final SpringDataCartItemRepository itemRepository;

    public CartPersistenceAdapter(SpringDataCartRepository cartRepository,
                                   SpringDataCartItemRepository itemRepository) {
        this.cartRepository = cartRepository;
        this.itemRepository = itemRepository;
    }

    @Override
    public Optional<Cart> loadByUserId(Long userId) {
        return cartRepository.findByUserId(userId).map(this::toDomainWithItems);
    }

    @Override
    @Transactional
    public Cart save(Cart cart) {
        CartJpaEntity entity;
        if (cart.getId() == null) {
            entity = new CartJpaEntity(null, cart.getUserId(), cart.getLastActiveAt(),
                    cart.getCreatedAt(), cart.getUpdatedAt());
        } else {
            entity = cartRepository.findById(cart.getId())
                    .orElseThrow(() -> new IllegalStateException("Cart not found: " + cart.getId()));
            entity.applyState(cart.getLastActiveAt(), cart.getUpdatedAt());
        }
        CartJpaEntity savedCart = cartRepository.save(entity);

        // 자식 일괄 교체 — 단순 정책
        if (cart.getId() != null) {
            itemRepository.deleteByCartId(savedCart.getId());
        }
        for (CartItem item : cart.getItems()) {
            itemRepository.save(new CartItemJpaEntity(
                    null, savedCart.getId(), item.getProductId(),
                    item.getVariantId(), item.getQuantity(), item.getAddedAt()
            ));
        }
        return toDomainWithItems(savedCart);
    }

    private Cart toDomainWithItems(CartJpaEntity e) {
        List<CartItem> items = itemRepository.findByCartIdOrderByAddedAtAsc(e.getId()).stream()
                .map(CartPersistenceAdapter::toItemDomain)
                .toList();
        return Cart.rehydrate(e.getId(), e.getUserId(), e.getLastActiveAt(),
                e.getCreatedAt(), e.getUpdatedAt(), items);
    }

    private static CartItem toItemDomain(CartItemJpaEntity e) {
        return CartItem.rehydrate(e.getId(), e.getCartId(), e.getProductId(),
                e.getVariantId(), e.getQuantity(), e.getAddedAt());
    }
}
