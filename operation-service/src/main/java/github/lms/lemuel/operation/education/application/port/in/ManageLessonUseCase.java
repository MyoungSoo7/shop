package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.domain.Lesson;
import github.lms.lemuel.operation.education.domain.exception.LessonNotInCourseException;
import github.lms.lemuel.operation.education.domain.exception.LessonOrderViolationException;

import java.util.List;
import java.util.UUID;

/**
 * 차시 등록·수정·삭제·재정렬 창구.
 *
 * <p>모든 메서드가 {@code courseId} 를 받는다. 차시 id 만으로도 찾을 수는 있지만, 그러면 경로가
 * "이 과정의 이 차시" 라고 말하는데 서버는 차시 id 만 확인하게 되고, 다른 과정의 차시를 남의
 * 경로로 고칠 수 있게 된다.
 */
public interface ManageLessonUseCase {

    /**
     * 차시를 만든다. {@code sequence} 를 커맨드에 넣지 않고 따로 받는 이유는 <b>수정과 이동이
     * 다른 일</b>이기 때문이다 — 같은 레코드에 담으면 내용만 고치려는 수정 요청이 순서까지 조용히
     * 덮어쓴다. 순서 변경은 {@link #reorder} 하나가 맡는다.
     */
    Lesson create(UUID courseId, int sequence, SaveCommand command, String actor);

    /** @throws LessonNotInCourseException 그 차시가 이 과정 소속이 아닐 때 */
    Lesson update(UUID courseId, UUID lessonId, SaveCommand command, String actor);

    /**
     * 지운다. 없는 차시의 삭제는 조용히 통과한다(삭제는 멱등). 존재하는데 다른 과정 소속이면
     * 거부한다 — 지우고 나서야 "그 과정이 아니었다"는 사실을 알게 되면 되돌릴 방법이 없다.
     *
     * @throws LessonNotInCourseException 그 차시가 존재하는데 이 과정 소속이 아닐 때
     */
    void delete(UUID courseId, UUID lessonId, String actor);

    /**
     * 요청한 순서대로 재정렬한다. 요청 목록은 그 과정의 차시 전체와 <b>정확히 같은 집합</b>이어야
     * 한다 — 일부만 보내면 빠진 차시의 순서가 무엇이 되어야 하는지 요청이 말하지 않는다.
     *
     * @throws LessonOrderViolationException 요청 목록이 현재 차시 집합과 다를 때
     */
    void reorder(UUID courseId, List<UUID> lessonIdsInOrder, String actor);

    /** 차시의 내용. 순서는 여기 없다 — 위 {@link #create} 주석 참조. */
    record SaveCommand(String title, String description, String contentType, String contentRef,
                       boolean required) {
    }
}
