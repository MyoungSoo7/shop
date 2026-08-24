package github.lms.lemuel.operation.education.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LessonRepository extends JpaRepository<LessonJpaEntity, UUID> {
    List<LessonJpaEntity> findAllByCourseIdOrderBySequence(UUID courseId);
}
