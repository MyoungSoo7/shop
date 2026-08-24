package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataOptionAxisRepository extends JpaRepository<OptionAxisJpaEntity, Long> {

    Optional<OptionAxisJpaEntity> findByCode(String code);

    List<OptionAxisJpaEntity> findAllByOrderByCodeAsc();
}
