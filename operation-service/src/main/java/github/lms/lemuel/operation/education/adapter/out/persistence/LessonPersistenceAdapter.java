package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.application.port.out.DeleteLessonPort;
import github.lms.lemuel.operation.education.application.port.out.LoadLessonPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLessonPort;
import github.lms.lemuel.operation.education.domain.Lesson;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 차시 영속 어댑터. */
@Component
public class LessonPersistenceAdapter implements LoadLessonPort, SaveLessonPort, DeleteLessonPort {

    private final LessonRepository lessons;

    public LessonPersistenceAdapter(LessonRepository lessons) { this.lessons = lessons; }

    @Override
    public List<Lesson> findByCourseOrderedBySequence(UUID courseId) {
        return lessons.findAllByCourseIdOrderBySequence(courseId).stream().map(LessonJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Lesson> findById(UUID id) {
        return lessons.findById(id).map(LessonJpaEntity::toDomain);
    }

    @Override
    public Lesson save(Lesson lesson) {
        LessonJpaEntity entity = lessons.findById(lesson.id()).orElse(null);
        if (entity == null) {
            entity = LessonJpaEntity.fromDomain(lesson);
        } else {
            entity.sync(lesson);
        }
        return lessons.save(entity).toDomain();
    }

    @Override
    public void deleteById(UUID id) { lessons.deleteById(id); }
}
