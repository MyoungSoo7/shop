package github.lms.lemuel.cart.domain;

import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 장바구니 도메인 (집합 루트).
 *
 * <p>특징:
 * <ul>
 *   <li>사용자당 1 개의 장바구니. 항목 추가/수량 변경/삭제 모두 도메인 메서드로 일관 처리</li>
 *   <li>같은 (productId, variantId) 추가는 수량 증가로 자동 변환 — 운영 사고 방지</li>
 *   <li>가격은 저장하지 않음 — 결제 시점 (checkout) 의 상품 가격이 진실의 원천</li>
 *   <li>{@code lastActiveAt} 으로 30일 TTL 배치 정리 (별도 cron job)</li>
 * </ul>
 */
public class Cart {

    private Long id;
    private final Long userId;
    private LocalDateTime lastActiveAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final List<CartItem> items = new ArrayList<>();

    public static Cart createEmpty(Long userId) {
        Objects.requireNonNull(userId, "userId");
        LocalDateTime now = LocalDateTime.now();
        return new Cart(null, userId, now, now, now, new ArrayList<>());
    }

    public static Cart rehydrate(Long id, Long userId, LocalDateTime lastActiveAt,
                                  LocalDateTime createdAt, LocalDateTime updatedAt,
                                  List<CartItem> items) {
        Cart cart = new Cart(id, userId, lastActiveAt, createdAt, updatedAt, new ArrayList<>());
        if (items != null) cart.items.addAll(items);
        return cart;
    }

    private Cart(Long id, Long userId, LocalDateTime lastActiveAt,
                 LocalDateTime createdAt, LocalDateTime updatedAt, List<CartItem> items) {
        this.id = id;
        this.userId = userId;
        this.lastActiveAt = lastActiveAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.items.addAll(items);
    }

    /**
     * 항목 추가. 같은 (productId, variantId) 가 이미 있으면 수량 증가, 없으면 새 라인.
     */
    public CartItem addItem(Long productId, Long variantId, int quantity) {
        Optional<CartItem> existing = findItem(productId, variantId);
        CartItem item;
        if (existing.isPresent()) {
            item = existing.get();
            item.increaseQuantity(quantity);
        } else {
            item = CartItem.newItem(productId, variantId, quantity);
            items.add(item);
        }
        touch();
        return item;
    }

    /**
     * 수량을 새 값으로 교체. 0 이면 삭제로 변환.
     */
    public void changeQuantity(Long productId, Long variantId, int newQuantity) {
        if (newQuantity == 0) {
            removeItem(productId, variantId);
            return;
        }
        CartItem item = findItem(productId, variantId)
                .orElseThrow(() -> new CartInvariantViolationException(
                        "장바구니에 없는 항목: productId=" + productId + ", variantId=" + variantId));
        item.changeQuantity(newQuantity);
        touch();
    }

    public void removeItem(Long productId, Long variantId) {
        Iterator<CartItem> it = items.iterator();
        while (it.hasNext()) {
            CartItem item = it.next();
            if (item.matches(productId, variantId)) {
                it.remove();
                touch();
                return;
            }
        }
    }

    public void clear() {
        items.clear();
        touch();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int totalQuantity() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    private Optional<CartItem> findItem(Long productId, Long variantId) {
        return items.stream().filter(i -> i.matches(productId, variantId)).findFirst();
    }

    private void touch() {
        LocalDateTime now = LocalDateTime.now();
        this.lastActiveAt = now;
        this.updatedAt = now;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1회만 부여");
        this.id = id;
    }

    public void attachItemsToCart() {
        if (id == null) throw new IllegalStateException("Cart id 부여 후 호출");
        for (CartItem item : items) item.attachToCart(id);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<CartItem> getItems() {
        return List.copyOf(items);
    }

    public void replaceItems(List<CartItem> reloaded) {
        items.clear();
        if (reloaded != null) items.addAll(reloaded);
    }
}
