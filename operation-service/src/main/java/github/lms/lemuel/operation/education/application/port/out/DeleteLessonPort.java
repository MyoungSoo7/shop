package github.lms.lemuel.operation.education.application.port.out;

import java.util.UUID;

/** 차시 삭제 포트. */
@FunctionalInterface
public interface DeleteLessonPort {
    void deleteById(UUID id);
}
