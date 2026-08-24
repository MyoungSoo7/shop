package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class ProductVariantPersistenceAdapter
        implements LoadProductVariantPort, SaveProductVariantPort {

    private final SpringDataProductVariantRepository repository;

    public ProductVariantPersistenceAdapter(SpringDataProductVariantRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ProductVariant> loadById(Long id) {
        return repository.findById(id).map(ProductVariantPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<ProductVariant> loadBySku(String sku) {
        return repository.findBySku(sku).map(ProductVariantPersistenceAdapter::toDomain);
    }

    @Override
    public List<ProductVariant> loadByProductId(Long productId) {
        return repository.findByProductId(productId).stream()
                .map(ProductVariantPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<ProductVariant> loadByOptionSignature(Long productId, String optionSignature) {
        if (optionSignature == null) {
            return Optional.empty();
        }
        return repository.findByProductIdAndOptionSignature(productId, optionSignature)
                .map(ProductVariantPersistenceAdapter::toDomain);
    }

    @Override
    public List<Long> findProductIdsWithVariants() {
        return repository.findDistinctProductIds();
    }

    @Override
    public ProductVariant save(ProductVariant variant) {
        ProductVariantJpaEntity entity;
        if (variant.getId() == null) {
            entity = new ProductVariantJpaEntity(
                    null, variant.getProductId(), variant.getSku(), variant.getOptionName(),
                    variant.getAdditionalPrice(), variant.getDiscountPrice(), variant.getDiscountRate(),
                    variant.getStockQuantity(), variant.getVersion(),
                    variant.getStatus(), variant.getOptionSignature(),
                    variant.getCreatedAt(), variant.getUpdatedAt()
            );
        } else {
            // 변경 감지: 기존 엔티티 로드 → @Version 보존된 채로 도메인 상태만 반영
            entity = repository.findById(variant.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ProductVariant 사라짐 (id=" + variant.getId() + ")"));
            entity.applyDomainState(
                    variant.getStockQuantity(),
                    variant.getStatus(),
                    variant.getOptionName(),
                    variant.getAdditionalPrice(),
                    variant.getUpdatedAt()
            );
            entity.applyOptionSignature(variant.getOptionSignature());
        }
        ProductVariantJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public int decreaseStockIfAvailable(Long variantId, int quantity) {
        return repository.decreaseStockIfAvailable(variantId, quantity, LocalDateTime.now());
    }

    @Override
    public int increaseStock(Long variantId, int quantity) {
        return repository.increaseStock(variantId, quantity, LocalDateTime.now());
    }

    private static ProductVariant toDomain(ProductVariantJpaEntity e) {
        return ProductVariant.rehydrate(
                e.getId(), e.getProductId(), e.getSku(), e.getOptionName(),
                e.getAdditionalPrice(), e.getDiscountPrice(), e.getDiscountRate(),
                e.getStockQuantity(), e.getVersion(),
                e.getStatus(), e.getOptionSignature(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
