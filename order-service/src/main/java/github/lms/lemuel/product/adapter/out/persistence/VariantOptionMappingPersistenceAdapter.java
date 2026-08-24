package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.VariantOptionMappingPort;
import github.lms.lemuel.product.domain.ProductVariantOptionValue;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VariantOptionMappingPersistenceAdapter implements VariantOptionMappingPort {

    private final SpringDataProductVariantOptionValueRepository repository;

    public VariantOptionMappingPersistenceAdapter(
            SpringDataProductVariantOptionValueRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ProductVariantOptionValue> loadByVariantId(Long variantId) {
        return repository.findByVariantIdOrderByProductOptionAxisIdAsc(variantId).stream()
                .map(VariantOptionMappingPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ProductVariantOptionValue> loadByProductOptionValueId(Long productOptionValueId) {
        return repository.findByProductOptionValueId(productOptionValueId).stream()
                .map(VariantOptionMappingPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public ProductVariantOptionValue save(ProductVariantOptionValue mapping) {
        ProductVariantOptionValueId id = new ProductVariantOptionValueId(
                mapping.getVariantId(), mapping.getProductOptionAxisId());
        ProductVariantOptionValueJpaEntity entity = repository.findById(id)
                .orElseGet(() -> new ProductVariantOptionValueJpaEntity(
                        mapping.getVariantId(), mapping.getProductOptionAxisId(),
                        mapping.getProductOptionValueId()));
        entity.applyValue(mapping.getProductOptionValueId());
        return toDomain(repository.save(entity));
    }

    private static ProductVariantOptionValue toDomain(ProductVariantOptionValueJpaEntity e) {
        return ProductVariantOptionValue.of(e.getVariantId(), e.getProductOptionAxisId(),
                e.getProductOptionValueId());
    }
}
