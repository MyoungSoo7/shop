package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.CourseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CourseRepository extends JpaRepository<CourseJpaEntity, UUID> {
    Page<CourseJpaEntity> findByStatusAndTitleContainingIgnoreCase(CourseStatus status, String title, Pageable pageable);
    Page<CourseJpaEntity> findByTitleContainingIgnoreCase(String title, Pageable pageable);
}
