package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.out.DeleteLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.LoadLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.LoadLecturerPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLecturerPort;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.Lecturer;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import github.lms.lemuel.operation.education.domain.exception.InvalidLecturerStateException;
import github.lms.lemuel.operation.education.domain.exception.LecturerAlreadyAssignedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 강사 콘솔 — <b>배정이 한 번만 걸리는가</b>, 그리고 <b>삭제가 배정을 데려가지 않는가</b>.
 *
 * <p>도메인 테스트가 두 축(active/deleted)을 보므로 여기서 볼 것은 그 위층이다: 중복 배정 차단과
 * 삭제의 파급 범위. 둘 다 dentis 에서 실제로 어긋났던 지점이다 — 배정은 seq 를 max+1 로 발급할 뿐
 * 중복을 막지 않았고, 삭제는 delete_yn 만 세워 배정 테이블은 손대지 않은 채였다(그건 옳다).
 */
class LecturerAdminServiceTest {

    private final LoadLecturerPort loadLecturer = mock(LoadLecturerPort.class);
    private final SaveLecturerPort saveLecturer = mock(SaveLecturerPort.class);
    private final LoadLecturerAssignmentPort loadAssignment = mock(LoadLecturerAssignmentPort.class);
    private final SaveLecturerAssignmentPort saveAssignment = mock(SaveLecturerAssignmentPort.class);
    private final DeleteLecturerAssignmentPort deleteAssignment = mock(DeleteLecturerAssignmentPort.class);
    private final LoadCoursePort loadCourse = mock(LoadCoursePort.class);
    private final LecturerAdminService service = new LecturerAdminService(
            loadLecturer, saveLecturer, loadAssignment, saveAssignment, deleteAssignment, loadCourse);

    private final UUID lecturerId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    private Lecturer lecturer(boolean active, boolean deleted) {
        return Lecturer.rehydrate(lecturerId, "김강사", "Kim", "OO대학원", "OO치과", "10년", "외부 강사",
                "약력", "history", "메모", Set.of("보철"), Set.of("보철 실습"),
                active, deleted, deleted ? Instant.now() : null, "admin", 1L);
    }

    private Course course() {
        return Course.rehydrate(courseId, "정산 교육", "설명", CourseStatus.PUBLISHED,
                Instant.now(), null, 30, "admin", 1L);
    }

    @Test
    @DisplayName("활성 강사를 빈 과정에 배정한다")
    void assigns() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(true, false)));
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course()));
        when(loadAssignment.exists(courseId, lecturerId)).thenReturn(false);
        when(saveAssignment.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LecturerAssignment assignment = service.assign(lecturerId, courseId, "admin");

        assertThat(assignment.courseId()).isEqualTo(courseId);
        assertThat(assignment.lecturerId()).isEqualTo(lecturerId);
        assertThat(assignment.assignedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("이미 배정된 강사는 거절한다 — DB 제약에 맡기면 500 이 된다")
    void rejectsDuplicateAssignment() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(true, false)));
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course()));
        when(loadAssignment.exists(courseId, lecturerId)).thenReturn(true);

        assertThatThrownBy(() -> service.assign(lecturerId, courseId, "admin"))
                .isInstanceOf(LecturerAlreadyAssignedException.class);
        verify(saveAssignment, never()).save(any());
    }

    @Test
    @DisplayName("비활성 강사는 배정 전에 거절한다 — 중복 검사보다 먼저다")
    void rejectsInactiveAssignment() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(false, false)));
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course()));

        assertThatThrownBy(() -> service.assign(lecturerId, courseId, "admin"))
                .isInstanceOf(InvalidLecturerStateException.class);
        verify(saveAssignment, never()).save(any());
    }

    @Test
    @DisplayName("없는 과정에는 배정하지 않는다")
    void rejectsUnknownCourse() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(true, false)));
        when(loadCourse.findById(courseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(lecturerId, courseId, "admin"))
                .isInstanceOf(CourseAdminService.CourseNotFoundException.class);
    }

    @Test
    @DisplayName("없는 강사를 부르면 LecturerNotFound 다")
    void rejectsUnknownLecturer() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(lecturerId))
                .isInstanceOf(LecturerAdminService.LecturerNotFoundException.class);
    }

    @Test
    @DisplayName("삭제해도 배정은 지우지 않는다 — 진행 중인 과정의 강사 칸이 조용히 비면 안 된다")
    void deleteKeepsAssignments() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(true, false)));
        when(loadAssignment.findByLecturer(lecturerId)).thenReturn(List.of(
                new LecturerAssignment(UUID.randomUUID(), courseId, lecturerId, Instant.now(),
                        "admin", "정산 교육", "김강사")));
        when(saveLecturer.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Lecturer deleted = service.delete(lecturerId, "admin");

        assertThat(deleted.deleted()).isTrue();
        verify(deleteAssignment, never()).delete(any(), any());
    }

    @Test
    @DisplayName("없던 배정을 해제하면 false 다 — 화면이 '이미 해제됨'과 '실패'를 구분한다")
    void unassignReturnsFalseWhenAbsent() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(true, false)));
        when(deleteAssignment.delete(courseId, lecturerId)).thenReturn(false);

        assertThat(service.unassign(lecturerId, courseId, "admin")).isFalse();
    }

    @Test
    @DisplayName("배정을 해제하면 true 다")
    void unassignReturnsTrueWhenRemoved() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(true, false)));
        when(deleteAssignment.delete(courseId, lecturerId)).thenReturn(true);

        assertThat(service.unassign(lecturerId, courseId, "admin")).isTrue();
    }

    @Test
    @DisplayName("활성 토글은 도메인 규칙을 그대로 탄다 — 지운 강사는 되살아나지 않는다")
    void activationHonoursDomainRules() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.of(lecturer(false, true)));

        assertThatThrownBy(() -> service.changeActivation(lecturerId, true, "admin"))
                .isInstanceOf(InvalidLecturerStateException.class);
        verify(saveLecturer, never()).save(any());
    }

    @Test
    @DisplayName("등록은 활성 상태로 저장한다")
    void registersActive() {
        when(saveLecturer.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Lecturer registered = service.register("김강사", null, null, "OO치과", null, null, null, null,
                null, Set.of("보철"), Set.of(), "admin");

        assertThat(registered.active()).isTrue();
        assertThat(registered.deleted()).isFalse();
        assertThat(registered.majors()).containsExactly("보철");
    }

    @Test
    @DisplayName("keyword 가 null 이면 빈 문자열로 넘긴다 — 포트가 '전건'과 'null 검색'을 구분하지 않아도 되게")
    void nullKeywordBecomesEmpty() {
        service.list(null, false, new github.lms.lemuel.operation.education.application.port.out.dto.PageSpec(0, 20));

        verify(loadLecturer).search(org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq(false), any());
    }

    @Test
    @DisplayName("배정 목록 조회는 강사 존재를 먼저 확인한다 — 없는 강사에 빈 목록을 주면 오타가 '배정 없음'으로 보인다")
    void assignmentsRequireExistingLecturer() {
        when(loadLecturer.findById(lecturerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignmentsOfLecturer(lecturerId))
                .isInstanceOf(LecturerAdminService.LecturerNotFoundException.class);
        verify(loadAssignment, never()).findByLecturer(any());
    }
}
