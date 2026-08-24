package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataOptionAxisValueRepository
        extends JpaRepository<OptionAxisValueJpaEntity, Long> {

    Optional<OptionAxisValueJpaEntity> findByAxisIdAndCode(Long axisId, String code);

    List<OptionAxisValueJpaEntity> findByAxisIdOrderBySortOrderAscIdAsc(Long axisId);
}
