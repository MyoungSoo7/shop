package github.lms.lemuel.cart.domain;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 장바구니 라인 — Cart 의 자식.
 *
 * <p>같은 장바구니 안에서 같은 (productId, variantId) 조합은 1 개만 존재 — 같은 상품 추가 요청은
 * {@link Cart#addItem(Long, Long, int)} 가 자동으로 수량 증가로 변환한다.
 *
 * <p>가격은 보관하지 않는다. 결제 시점 (checkout) 의 상품 가격이 진실의 원천이며, 장바구니에
 * 가격을 캐싱하면 가격 변경 시 사용자에게 잘못된 금액을 보여주는 사고가 발생한다.
 */
public class CartItem {

    private Long id;
    private Long cartId;
    private final Long productId;
    private final Long variantId;
    private int quantity;
    private final LocalDateTime addedAt;

    public static CartItem newItem(Long productId, Long variantId, int quantity) {
        Objects.requireNonNull(productId, "productId");
        if (quantity <= 0) {
            throw new CartInvariantViolationException("quantity 는 양수여야 합니다");
        }
        return new CartItem(null, null, productId, variantId, quantity, LocalDateTime.now());
    }

    public static CartItem rehydrate(Long id, Long cartId, Long productId, Long variantId,
                                      int quantity, LocalDateTime addedAt) {
        return new CartItem(id, cartId, productId, variantId, quantity, addedAt);
    }

    private CartItem(Long id, Long cartId, Long productId, Long variantId,
                     int quantity, LocalDateTime addedAt) {
        this.id = id;
        this.cartId = cartId;
        this.productId = productId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.addedAt = addedAt;
    }

    void increaseQuantity(int delta) {
        if (delta <= 0) {
            throw new CartInvariantViolationException("증가량은 양수여야 합니다");
        }
        this.quantity += delta;
    }

    void changeQuantity(int newQuantity) {
        if (newQuantity <= 0) {
            throw new CartInvariantViolationException("수량은 양수여야 합니다 (0 은 removeItem 으로 처리)");
        }
        this.quantity = newQuantity;
    }

    void attachToCart(Long cartId) {
        this.cartId = cartId;
    }

    boolean matches(Long productId, Long variantId) {
        return Objects.equals(this.productId, productId) && Objects.equals(this.variantId, variantId);
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1회만 부여");
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getCartId() { return cartId; }
    public Long getProductId() { return productId; }
    public Long getVariantId() { return variantId; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getAddedAt() { return addedAt; }
}
