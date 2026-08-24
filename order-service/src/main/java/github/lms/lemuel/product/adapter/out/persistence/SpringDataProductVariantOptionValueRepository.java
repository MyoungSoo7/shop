package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataProductVariantOptionValueRepository
        extends JpaRepository<ProductVariantOptionValueJpaEntity, ProductVariantOptionValueId> {

    List<ProductVariantOptionValueJpaEntity> findByVariantIdOrderByProductOptionAxisIdAsc(Long variantId);

    List<ProductVariantOptionValueJpaEntity> findByProductOptionValueId(Long productOptionValueId);
}
