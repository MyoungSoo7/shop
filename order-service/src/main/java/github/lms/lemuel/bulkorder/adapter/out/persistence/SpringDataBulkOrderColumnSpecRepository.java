package github.lms.lemuel.bulkorder.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataBulkOrderColumnSpecRepository
        extends JpaRepository<BulkOrderColumnSpecJpaEntity, Long> {

    List<BulkOrderColumnSpecJpaEntity> findAllByOrderByColumnIndexAsc();
}
