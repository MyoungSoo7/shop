package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataProductOptionValueRepository
        extends JpaRepository<ProductOptionValueJpaEntity, Long> {

    List<ProductOptionValueJpaEntity> findByProductOptionAxisIdOrderBySortOrderAscIdAsc(
            Long productOptionAxisId);

    Optional<ProductOptionValueJpaEntity> findByProductOptionAxisIdAndAxisValueId(
            Long productOptionAxisId, Long axisValueId);
}
