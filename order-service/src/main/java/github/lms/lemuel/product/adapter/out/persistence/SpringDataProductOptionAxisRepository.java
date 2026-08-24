package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataProductOptionAxisRepository
        extends JpaRepository<ProductOptionAxisJpaEntity, Long> {

    List<ProductOptionAxisJpaEntity> findByProductIdOrderBySortOrderAsc(Long productId);

    Optional<ProductOptionAxisJpaEntity> findByProductIdAndAxisId(Long productId, Long axisId);
}
