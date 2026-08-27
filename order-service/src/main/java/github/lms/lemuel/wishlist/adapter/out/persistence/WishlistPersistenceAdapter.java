package github.lms.lemuel.wishlist.adapter.out.persistence;

import github.lms.lemuel.wishlist.application.port.out.LoadWishlistPort;
import github.lms.lemuel.wishlist.application.port.out.SaveWishlistPort;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 찜 영속성 어댑터 (JPA/PostgreSQL).
 *
 * <p>중복 방지는 이 계층이 아니라 <b>{@code UNIQUE (user_id, product_id)} 제약</b>이 한다.
 * 여기서 미리 조회해 걸러 봐야 조회와 저장 사이의 틈은 남고, 그 틈이 레거시에서 실제로 중복 행을
 * 만들었다. 제약이 거부하면 {@code DataIntegrityViolationException} 을 <b>그대로 올린다</b> —
 * 그것이 오류인지 멱등인지는 서비스가 판단할 일이지 어댑터가 삼킬 일이 아니다.
 */
@Component
public class WishlistPersistenceAdapter implements LoadWishlistPort, SaveWishlistPort {

    private final SpringDataWishlistItemRepository repository;

    public WishlistPersistenceAdapter(SpringDataWishlistItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<WishlistItem> findByUserId(Long userId) {
        return repository.findByUserIdOrderByAddedAtDesc(userId).stream()
                .map(WishlistPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public boolean exists(Long userId, Long productId) {
        return repository.existsByUserIdAndProductId(userId, productId);
    }

    @Override
    public long countByUserId(Long userId) {
        return repository.countByUserId(userId);
    }

    @Override
    @Transactional
    public WishlistItem save(WishlistItem item) {
        WishlistItemJpaEntity saved = repository.save(new WishlistItemJpaEntity(
                item.id(), item.userId(), item.productId(), item.addedAt()));
        return toDomain(saved);
    }

    @Override
    @Transactional
    public boolean deleteByUserIdAndProductId(Long userId, Long productId) {
        return repository.deleteByUserIdAndProductId(userId, productId) > 0;
    }

    @Override
    @Transactional
    public int deleteByUserIdAndProductIds(Long userId, Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            // 빈 IN 절은 DB 마다 다르게 취급된다. 지울 것이 없다는 사실을 쿼리로 물어볼 이유가 없다.
            return 0;
        }
        return (int) repository.deleteByUserIdAndProductIdIn(userId, productIds);
    }

    private static WishlistItem toDomain(WishlistItemJpaEntity e) {
        return WishlistItem.rehydrate(e.getId(), e.getUserId(), e.getProductId(), e.getAddedAt());
    }
}
