package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.domain.Lesson;

/** 차시 저장 포트. */
@FunctionalInterface
public interface SaveLessonPort {
    Lesson save(Lesson lesson);
}
