package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;

import java.util.UUID;

/**
 * 과정 등록·수정·상태 전이 창구.
 *
 * <p>{@code actor} 를 커맨드에 넣지 않고 따로 받는다. 커맨드는 <b>요청 본문에서 온 것</b>이고
 * actor 는 <b>인증에서 온 것</b>이라 출처가 다르다. 한 레코드에 섞으면 클라이언트가 보낸 이름으로
 * 감사 로그가 남는 실수가 타입 위에 드러나지 않는다.
 */
public interface ManageCourseUseCase {

    Course create(SaveCommand command, String actor);

    /** @throws CourseNotFoundException 해당 id 의 과정이 없을 때 */
    Course update(UUID id, SaveCommand command, String actor);

    /**
     * 상태를 옮긴다. 받는 것은 {@code PUBLISHED}·{@code HIDDEN}·{@code CLOSED} 셋뿐이다 —
     * {@code DRAFT} 로 되돌리는 전이는 없다. 공개했다가 초안으로 되돌리면 이미 신청한 사람들의
     * 신청서가 존재하지 않는 과정에 매달린다.
     *
     * @throws CourseNotFoundException  해당 id 의 과정이 없을 때
     * @throws IllegalArgumentException 위 셋이 아닌 상태를 목표로 줬을 때
     */
    Course transition(UUID id, CourseStatus target, String actor);

    record SaveCommand(String title, String description) {
    }
}
