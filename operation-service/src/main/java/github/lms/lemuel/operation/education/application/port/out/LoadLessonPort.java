package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.domain.Lesson;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 차시 조회 포트. */
public interface LoadLessonPort {

    /** 과정의 차시를 순서(sequence) 오름차순으로 돌려준다. */
    List<Lesson> findByCourseOrderedBySequence(UUID courseId);

    Optional<Lesson> findById(UUID id);
}
