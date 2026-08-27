package github.lms.lemuel.wishlist.application.port.out;

import github.lms.lemuel.wishlist.domain.WishlistItem;

import java.util.Collection;

public interface SaveWishlistPort {

    /**
     * 담는다.
     *
     * <p>같은 (userId, productId) 가 이미 있으면 DB 유니크 제약이 거부한다 — 어댑터는 그 예외를
     * 삼키지 않고 그대로 올린다. 무엇이 멱등이고 무엇이 오류인지 판단하는 자리는 서비스다.
     */
    WishlistItem save(WishlistItem item);

    /** @return 실제로 지워졌으면 true, 원래 없었으면 false */
    boolean deleteByUserIdAndProductId(Long userId, Long productId);

    /** @return 지워진 행 수 */
    int deleteByUserIdAndProductIds(Long userId, Collection<Long> productIds);
}
