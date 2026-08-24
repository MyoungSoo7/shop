package github.lms.lemuel.category.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataDisplaySectionRepository extends JpaRepository<DisplaySectionJpaEntity, Long> {

    Optional<DisplaySectionJpaEntity> findByCode(String code);

    List<DisplaySectionJpaEntity> findAllByOrderBySortOrderAscIdAsc();
}
