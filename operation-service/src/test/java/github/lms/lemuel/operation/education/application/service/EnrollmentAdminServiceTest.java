package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.in.CapacitySummary;
import github.lms.lemuel.operation.education.application.port.in.ManageEnrollmentUseCase;
import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.LoadEnrollmentPort;
import github.lms.lemuel.operation.education.application.port.out.SaveCoursePort;
import github.lms.lemuel.operation.education.application.port.out.SaveEnrollmentPort;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import github.lms.lemuel.operation.education.domain.exception.CourseCapacityExceededException;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.EnrollmentNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수강 신청 콘솔 — <b>정원이 실제로 지켜지는가</b>.
 *
 * <p>도메인 테스트가 "정원이 찼을 때 거절하는가"를 보므로, 여기서 볼 것은 그 위층이다:
 * 서비스가 <b>세는 수를 어디서 가져오는가</b>. 확정 수를 목록 길이로 세면 정원이 페이지 크기로
 * 둔갑하므로, 전용 카운트 포트를 부르는지까지 고정한다.
 */
class EnrollmentAdminServiceTest {

    private final LoadEnrollmentPort loadEnrollment = mock(LoadEnrollmentPort.class);
    private final SaveEnrollmentPort saveEnrollment = mock(SaveEnrollmentPort.class);
    private final LoadCoursePort loadCourse = mock(LoadCoursePort.class);
    private final SaveCoursePort saveCourse = mock(SaveCoursePort.class);
    private final EnrollmentAdminService service =
            new EnrollmentAdminService(loadEnrollment, saveEnrollment, loadCourse, saveCourse);

    private final UUID courseId = UUID.randomUUID();

    private Course course(Integer capacity, CourseStatus status) {
        return Course.rehydrate(courseId, "정산 교육", "설명", status, Instant.now(), null, capacity, "admin", 1L);
    }

    private Enrollment waiting(UUID id) {
        return Enrollment.rehydrate(id, courseId, "u-1", "김운영", "OO치과", EnrollmentStatus.WAITING,
                null, null, Instant.now(), null, null, "admin", 0L);
    }

    @Test
    @DisplayName("자리가 남아 있으면 확정한다")
    void confirmsWhenSeatIsFree() {
        UUID id = UUID.randomUUID();
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(3, CourseStatus.PUBLISHED)));
        when(loadEnrollment.findById(id)).thenReturn(Optional.of(waiting(id)));
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED)).thenReturn(2L);
        when(saveEnrollment.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Enrollment confirmed = service.confirm(id, "admin");

        assertThat(confirmed.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
        // 목록이 아니라 카운트 포트로 세야 한다 — 페이지 하나만 세면 정원이 페이지 크기가 된다.
        verify(loadEnrollment).countByStatus(courseId, EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("정원이 찼으면 확정하지 않고 저장도 하지 않는다")
    void refusesToConfirmWhenFull() {
        UUID id = UUID.randomUUID();
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(3, CourseStatus.PUBLISHED)));
        when(loadEnrollment.findById(id)).thenReturn(Optional.of(waiting(id)));
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED)).thenReturn(3L);

        assertThatThrownBy(() -> service.confirm(id, "admin"))
                .isInstanceOf(CourseCapacityExceededException.class);
        // 초과 확정을 저장해 두고 나중에 되돌리면, 누구를 내보낼지 사람이 골라야 하는 상황이 된다.
        verify(saveEnrollment, never()).save(any());
    }

    @Test
    @DisplayName("정원이 없으면(null) 확정을 막지 않는다")
    void unlimitedCourseNeverBlocksConfirm() {
        UUID id = UUID.randomUUID();
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(null, CourseStatus.PUBLISHED)));
        when(loadEnrollment.findById(id)).thenReturn(Optional.of(waiting(id)));
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED)).thenReturn(500L);
        when(saveEnrollment.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.confirm(id, "admin").status()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("끝난 과정에는 신청을 받지 않는다 — 아무도 확정해 주지 않을 대기표가 된다")
    void closedCourseRejectsRegistration() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(10, CourseStatus.CLOSED)));

        assertThatThrownBy(() -> service.register(new ManageEnrollmentUseCase.RegisterCommand(courseId, "u-9", "김신청", null), "admin"))
                .isInstanceOf(InvalidCourseStateException.class);
        verify(saveEnrollment, never()).save(any());
    }

    @Test
    @DisplayName("없는 과정에 신청을 꽂지 않는다")
    void missingCourseIsReported() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(new ManageEnrollmentUseCase.RegisterCommand(courseId, "u-9", "김신청", null), "admin"))
                .isInstanceOf(CourseNotFoundException.class);
    }

    @Test
    @DisplayName("없는 신청을 확정하려 하면 그렇게 말한다")
    void missingEnrollmentIsReported() {
        when(loadEnrollment.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(UUID.randomUUID(), "admin"))
                .isInstanceOf(EnrollmentNotFoundException.class);
    }

    @Test
    @DisplayName("현황은 정원·확정·대기·취소를 한 번에 읽는다")
    void summaryReadsAllCountsTogether() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(30, CourseStatus.PUBLISHED)));
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED)).thenReturn(28L);
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.WAITING)).thenReturn(5L);
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CANCELLED)).thenReturn(2L);

        CapacitySummary summary = service.summary(courseId);

        assertThat(summary.capacity()).isEqualTo(30);
        assertThat(summary.confirmed()).isEqualTo(28L);
        assertThat(summary.waiting()).isEqualTo(5L);
        assertThat(summary.remaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("정원 없음이면 잔여도 없음이다 — 0 으로 내려보내면 화면이 마감으로 읽는다")
    void unlimitedSummaryHasNullRemaining() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(null, CourseStatus.PUBLISHED)));

        assertThat(service.summary(courseId).remaining()).isNull();
    }

    @Test
    @DisplayName("확정 인원보다 작게 정원을 줄이려 하면 과정을 저장하지 않는다")
    void capacityReductionBelowConfirmedIsRejected() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(10, CourseStatus.PUBLISHED)));
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED)).thenReturn(7L);

        assertThatThrownBy(() -> service.changeCapacity(courseId, 3, "admin"))
                .isInstanceOf(CourseCapacityExceededException.class);
        verify(saveCourse, never()).save(any());
    }

    @Test
    @DisplayName("정원을 늘리면 현황의 잔여가 그만큼 늘어난다")
    void capacityIncreaseIsReflectedInSummary() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(10, CourseStatus.PUBLISHED)));
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED)).thenReturn(7L);
        when(loadEnrollment.countByStatus(courseId, EnrollmentStatus.WAITING)).thenReturn(4L);
        when(saveCourse.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CapacitySummary summary = service.changeCapacity(courseId, 20, "admin");

        assertThat(summary.capacity()).isEqualTo(20);
        assertThat(summary.remaining()).isEqualTo(13);
        verify(saveCourse).save(any());
    }

    @Test
    @DisplayName("취소는 사유를 그대로 싣는다")
    void cancelCarriesReason() {
        UUID id = UUID.randomUUID();
        when(loadEnrollment.findById(id)).thenReturn(Optional.of(waiting(id)));
        when(saveEnrollment.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Enrollment cancelled = service.cancel(id, "본인 요청", "admin");

        assertThat(cancelled.status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("본인 요청");
    }

    @Test
    @DisplayName("신청은 대기로 접수된다 — 콘솔에서 등록해도 자동 확정이 아니다")
    void registerStartsWaiting() {
        when(loadCourse.findById(courseId)).thenReturn(Optional.of(course(10, CourseStatus.PUBLISHED)));
        when(saveEnrollment.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Enrollment registered = service.register(new ManageEnrollmentUseCase.RegisterCommand(courseId, "u-9", "김신청", "OO치과"), "admin");

        assertThat(registered.status()).isEqualTo(EnrollmentStatus.WAITING);
        assertThat(registered.courseId()).isEqualTo(courseId);
    }
}
