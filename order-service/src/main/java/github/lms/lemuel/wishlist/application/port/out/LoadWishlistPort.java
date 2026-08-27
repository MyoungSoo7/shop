package github.lms.lemuel.wishlist.application.port.out;

import github.lms.lemuel.wishlist.domain.WishlistItem;

import java.util.List;

public interface LoadWishlistPort {

    /** 담은 시각 역순(최근이 위). 정렬을 저장소가 책임진다 — 인덱스가 받아 주는 자리다. */
    List<WishlistItem> findByUserId(Long userId);

    boolean exists(Long userId, Long productId);

    long countByUserId(Long userId);
}
