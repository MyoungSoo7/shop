package github.lms.lemuel.wishlist.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SpringDataWishlistItemRepository extends JpaRepository<WishlistItemJpaEntity, Long> {

    /** 최근 담은 것이 위. 정렬을 여기서 못박아 두면 호출부가 매번 정하지 않아도 된다. */
    List<WishlistItemJpaEntity> findByUserIdOrderByAddedAtDesc(Long userId);

    boolean existsByUserIdAndProductId(Long userId, Long productId);

    long countByUserId(Long userId);

    /** @return 지워진 행 수 (0 이면 원래 없었다) */
    long deleteByUserIdAndProductId(Long userId, Long productId);

    /** @return 지워진 행 수 */
    long deleteByUserIdAndProductIdIn(Long userId, Collection<Long> productIds);
}
