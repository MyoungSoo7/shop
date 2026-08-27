package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.domain.Lecturer;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.InvalidLecturerStateException;
import github.lms.lemuel.operation.education.domain.exception.LecturerAlreadyAssignedException;
import github.lms.lemuel.operation.education.domain.exception.LecturerNotFoundException;

import java.util.Set;
import java.util.UUID;

/**
 * 강사 등록·수정·활성화·삭제와 과정 배정 창구.
 *
 * <p>배정과 명부를 한 창구에 둔 이유는 둘이 같은 불변식을 공유하기 때문이다 — 비활성·삭제된
 * 강사는 새 배정을 받지 않는다. 나누면 그 규칙이 어느 쪽 것인지 애매해지고, 한쪽만 고쳐진다.
 */
public interface ManageLecturerUseCase {

    Lecturer register(SaveCommand command, String actor);

    /** @throws LecturerNotFoundException 해당 id 의 강사가 없을 때 */
    Lecturer update(UUID id, SaveCommand command, String actor);

    /**
     * 활성/비활성 토글. 비활성 강사는 새 배정을 받지 않지만 <b>이미 맡은 과정에서 빠지지는 않는다</b> —
     * 빼면 진행 중인 과정의 강사 칸이 조용히 비고, 원래 무인이었는지 강사를 뺀 것인지 구분할 수 없다.
     *
     * @throws LecturerNotFoundException 해당 id 의 강사가 없을 때
     */
    Lecturer changeActivation(UUID id, boolean active, String actor);

    /** 명부에서 뺀다. 배정은 함께 지우지 않는다 — 이유는 {@link #changeActivation} 과 같다.
     *
     * @throws LecturerNotFoundException 해당 id 의 강사가 없을 때 */
    Lecturer delete(UUID id, String actor);

    /**
     * 과정에 배정한다.
     *
     * @throws LecturerNotFoundException        해당 강사가 없을 때
     * @throws CourseNotFoundException          해당 과정이 없을 때
     * @throws InvalidLecturerStateException    비활성·삭제된 강사일 때
     * @throws LecturerAlreadyAssignedException 이미 그 과정에 배정돼 있을 때 — 중복 배정을 허용하면
     *                                          해제가 한 번에 끝나지 않는다
     */
    LecturerAssignment assign(UUID lecturerId, UUID courseId, String actor);

    /**
     * 배정을 해제한다.
     *
     * @return 실제로 지웠으면 true, 애초에 없던 배정이면 false. 예외로 만들지 않는 이유는
     *         "이미 해제됨" 과 "실패" 를 부르는 쪽이 골라야 하기 때문이다 — HTTP 어댑터는 404 로
     *         옮기고, 일괄 정리 같은 호출자는 무시하면 된다.
     * @throws LecturerNotFoundException 해당 강사가 없을 때
     */
    boolean unassign(UUID lecturerId, UUID courseId, String actor);

    /**
     * 강사 등록·수정 스펙.
     *
     * <p>분야 둘({@code majors}·{@code lectureFields})은 집합이다. 중복·공백 제거는 도메인이 한다 —
     * 어댑터마다 걸러 내면 어느 하나가 빠졌을 때 같은 강사가 같은 분야를 두 번 갖는다.
     */
    record SaveCommand(String name, String englishName, String graduateSchool, String officeName,
                       String career, String lecturerType, String historyKo, String historyEn,
                       String etcMemo, Set<String> majors, Set<String> lectureFields) {
    }
}
