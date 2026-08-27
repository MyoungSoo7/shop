package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.domain.Lesson;

import java.util.List;
import java.util.UUID;

/**
 * 차시 조회 창구.
 *
 * <p>페이지네이션이 없다. 차시는 <b>순서가 곧 의미</b>인 목록이라, 페이지로 잘리면 재정렬 화면이
 * 애초에 성립하지 않는다.
 */
public interface QueryLessonUseCase {

    /** 그 과정의 차시를 순서대로. 과정이 없으면 빈 목록이다 — 조회는 없는 것을 오류로 만들지 않는다. */
    List<Lesson> list(UUID courseId);
}
