package github.lms.lemuel.wishlist.domain;

import java.util.Objects;

/**
 * 찜 한 줄 + 그 상품의 현재 모습.
 *
 * <p>찜 행({@link WishlistItem})은 담은 시점의 사실이고, 상품({@link WishlistProduct})은 <b>지금</b>의
 * 사실이다. 화면에 필요한 것은 둘을 합친 것이므로 합치는 자리를 값으로 둔다.
 *
 * <p>{@code product} 는 결코 {@code null} 이 아니다 — 상품이 삭제됐어도
 * {@link WishlistProduct#removed(Long)} 가 그 사실 자체를 값으로 들고 온다.
 */
public record WishlistEntry(WishlistItem item, WishlistProduct product) {

    public WishlistEntry {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(product, "product");
        if (!Objects.equals(item.productId(), product.productId())) {
            throw new IllegalStateException(
                    "찜 행과 상품이 어긋난다: item=" + item.productId() + ", product=" + product.productId());
        }
    }

    public Long productId() {
        return item.productId();
    }

    public boolean isAvailable() {
        return product.isAvailable();
    }

    /** 되살아날 여지가 없어 일괄 정리 대상인가. */
    public boolean isGone() {
        return product.isGone();
    }

    /** 사용자에게 보여 줄 상태 사유(구매 가능·품절·단종·…). */
    public String reason() {
        return product.availability().label();
    }
}
