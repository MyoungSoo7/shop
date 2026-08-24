package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.PublishEducationEventPort;
import github.lms.lemuel.operation.education.application.port.out.SaveCoursePort;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoursePublicationEventTest {

    private final LoadCoursePort loadCourse = mock(LoadCoursePort.class);
    private final SaveCoursePort saveCourse = mock(SaveCoursePort.class);
    private final PublishEducationEventPort events = mock(PublishEducationEventPort.class);
    private final CourseAdminService service = new CourseAdminService(loadCourse, saveCourse, events);

    private void savePassesThrough() {
        when(saveCourse.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void listSupportsStatusAndTitleFilters() {
        PageSpec page = new PageSpec(0, 20);
        when(loadCourse.search(any(), any(), any())).thenReturn(PageSlice.empty(page));

        service.list(null, null, page);
        service.list(CourseStatus.DRAFT, "정산", page);

        // query 가 null 이면 빈 문자열로 정규화해서 포트에 넘긴다.
        verify(loadCourse).search(null, "", page);
        verify(loadCourse).search(CourseStatus.DRAFT, "정산", page);
    }

    @Test
    void listReturnsWhatThePortFound() {
        Course course = Course.draft(UUID.randomUUID(), "교육", "설명", "admin");
        when(loadCourse.search(any(), any(), any()))
                .thenReturn(new PageSlice<>(List.of(course), 0, 20, 41L));

        PageSlice<Course> found = service.list(null, "교육", new PageSpec(0, 20));

        assertThat(found.content()).containsExactly(course);
        assertThat(found.totalElements()).isEqualTo(41L);
        assertThat(found.totalPages()).isEqualTo(3);
    }

    @Test
    void createUpdateHideAndCloseArePersisted() {
        Course course = Course.draft(UUID.randomUUID(), "교육", "설명", "admin");
        savePassesThrough();
        when(loadCourse.findById(course.id())).thenReturn(Optional.of(course));

        service.create("새 교육", "설명", "admin");
        service.update(course.id(), "수정 교육", "수정 설명", "admin");
        service.transition(course.id(), CourseStatus.PUBLISHED, "admin");
        service.transition(course.id(), CourseStatus.HIDDEN, "admin");
        service.transition(course.id(), CourseStatus.CLOSED, "admin");

        assertThat(course.title()).isEqualTo("수정 교육");
        assertThat(course.status()).isEqualTo(CourseStatus.CLOSED);
        verify(saveCourse, atLeastOnce()).save(any());
        verify(events).coursePublished(course, "admin");
    }

    @Test
    void missingCourseIsReported() {
        when(loadCourse.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(CourseAdminService.CourseNotFoundException.class);
    }

    @Test
    void publishingCourseWritesCoursePublishedEvent() {
        Course course = Course.draft(UUID.randomUUID(), "교육", "설명", "admin");
        savePassesThrough();
        when(loadCourse.findById(course.id())).thenReturn(Optional.of(course));

        service.transition(course.id(), CourseStatus.PUBLISHED, "admin");

        verify(events).coursePublished(course, "admin");
    }

    @Test
    void onlyPublishTransitionEmitsAnEvent() {
        Course course = Course.draft(UUID.randomUUID(), "교육", "설명", "admin");
        course.publish("admin");
        savePassesThrough();
        when(loadCourse.findById(course.id())).thenReturn(Optional.of(course));

        service.transition(course.id(), CourseStatus.HIDDEN, "admin");

        verify(events, never()).coursePublished(any(), any());
    }

    @Test
    void failedTransitionIsNeitherPersistedNorPublished() {
        Course course = Course.draft(UUID.randomUUID(), "교육", "설명", "admin");
        when(loadCourse.findById(course.id())).thenReturn(Optional.of(course));

        // DRAFT 에서 바로 HIDDEN 은 도메인이 막는다 — 저장·발행까지 가면 안 된다.
        assertThatThrownBy(() -> service.transition(course.id(), CourseStatus.HIDDEN, "admin"))
                .isInstanceOf(RuntimeException.class);

        verify(saveCourse, never()).save(any());
        verify(events, never()).coursePublished(any(), any());
    }

    @Test
    void unsupportedTransitionTargetIsRejected() {
        Course course = Course.draft(UUID.randomUUID(), "교육", "설명", "admin");
        when(loadCourse.findById(course.id())).thenReturn(Optional.of(course));

        assertThatThrownBy(() -> service.transition(course.id(), CourseStatus.DRAFT, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
