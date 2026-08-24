package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadPrimaryCategoryPort;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 대표 분류 조회 구현 — {@code product_ecommerce_categories} 를 소유한 카테고리 컨텍스트가 제공한다.
 *
 * <p>상품 쪽은 {@link LoadPrimaryCategoryPort} 인터페이스만 알고, 매핑 테이블의 존재도 형태도 모른다.
 */
@Component
public class PrimaryCategoryLookupAdapter implements LoadPrimaryCategoryPort {

    private final SpringDataProductEcommerceCategoryRepository repository;

    public PrimaryCategoryLookupAdapter(SpringDataProductEcommerceCategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Long> findPrimaryCategoryId(Long productId) {
        if (productId == null) {
            return Optional.empty();
        }
        return repository.findByProductIdAndPrimaryTrue(productId)
                .map(ProductEcommerceCategoryJpaEntity::getCategoryId);
    }

    @Override
    public Map<Long, Long> findPrimaryCategoryIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        return repository.findPrimaryByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductEcommerceCategoryJpaEntity::getProductId,
                        ProductEcommerceCategoryJpaEntity::getCategoryId, (a, b) -> a));
    }
}
