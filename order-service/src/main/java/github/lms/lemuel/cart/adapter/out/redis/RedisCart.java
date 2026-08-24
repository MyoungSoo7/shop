package github.lms.lemuel.cart.adapter.out.redis;

import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Redis 저장용 장바구니 직렬화 DTO.
 *
 * <p>도메인 {@link Cart} 는 no-arg 생성자/세터가 없어 Jackson 으로 직접 (역)직렬화할 수 없으므로
 * 평탄한 record 로 변환해 JSON 으로 저장한다. Redis 모드는 userId 가 곧 장바구니의 식별자이므로
 * 별도 surrogate id 를 보관하지 않는다 (JPA 의 auto-increment id 와 다른 점).
 */
public record RedisCart(
        Long userId,
        LocalDateTime lastActiveAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<Item> items
) {

    public record Item(Long productId, Long variantId, int quantity, LocalDateTime addedAt) {
        static Item from(CartItem i) {
            return new Item(i.getProductId(), i.getVariantId(), i.getQuantity(), i.getAddedAt());
        }
    }

    public static RedisCart from(Cart cart) {
        List<Item> items = cart.getItems().stream().map(Item::from).toList();
        return new RedisCart(cart.getUserId(), cart.getLastActiveAt(),
                cart.getCreatedAt(), cart.getUpdatedAt(), items);
    }

    /**
     * 도메인으로 복원한다. 표시/로그용 식별자가 필요하므로 cart id 는 userId 로,
     * item id 는 1-기반 순번으로 부여한다 (Redis 모드에는 영속 surrogate id 가 없다).
     */
    public Cart toDomain() {
        List<CartItem> domainItems = new java.util.ArrayList<>(items.size());
        long seq = 1;
        for (Item i : items) {
            domainItems.add(CartItem.rehydrate(seq++, userId, i.productId(),
                    i.variantId(), i.quantity(), i.addedAt()));
        }
        return Cart.rehydrate(userId, userId, lastActiveAt, createdAt, updatedAt, domainItems);
    }
}
