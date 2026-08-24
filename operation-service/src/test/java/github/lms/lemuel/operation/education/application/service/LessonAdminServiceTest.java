package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.out.DeleteLessonPort;
import github.lms.lemuel.operation.education.application.port.out.LoadLessonPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLessonPort;
import github.lms.lemuel.operation.education.domain.Lesson;
import github.lms.lemuel.operation.education.domain.LessonContentType;
import github.lms.lemuel.operation.education.domain.exception.LessonNotInCourseException;
import github.lms.lemuel.operation.education.domain.exception.LessonOrderViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LessonAdminServiceTest {

    private final LoadLessonPort loadLesson = mock(LoadLessonPort.class);
    private final SaveLessonPort saveLesson = mock(SaveLessonPort.class);
    private final DeleteLessonPort deleteLesson = mock(DeleteLessonPort.class);
    private final LessonAdminService service = new LessonAdminService(loadLesson, saveLesson, deleteLesson);

    private static Lesson lesson(UUID id, UUID courseId, String title, int sequence) {
        return Lesson.create(id, courseId, title, null, sequence, "VIDEO", "v" + sequence, true, "admin");
    }

    @Test
    void listCreateUpdateAndDeleteDelegateToPorts() {
        UUID courseId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        Lesson existing = lesson(lessonId, courseId, "차시", 1);
        when(loadLesson.findByCourseOrderedBySequence(courseId)).thenReturn(List.of(existing));
        when(loadLesson.findById(lessonId)).thenReturn(Optional.of(existing));
        when(saveLesson.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.list(courseId)).containsExactly(existing);
        assertThat(service.create(courseId, "새 차시", "설명", 2, "DOCUMENT", "d1", true, "admin")).isNotNull();
        Lesson updated = service.update(courseId, lessonId, "수정 차시", "설명", "EXTERNAL_LINK", "x1", false, "admin");
        service.delete(courseId, lessonId, "admin");

        assertThat(updated.title()).isEqualTo("수정 차시");
        assertThat(updated.contentType()).isEqualTo(LessonContentType.EXTERNAL_LINK);
        assertThat(updated.required()).isFalse();
        verify(deleteLesson).deleteById(lessonId);
    }

    @Test
    void updatingMissingLessonIsReported() {
        when(loadLesson.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), UUID.randomUUID(), "제목", null, "VIDEO", "v1", true, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(saveLesson, never()).save(any());
    }

    @Test
    void updateRejectsLessonThatBelongsToAnotherCourse() {
        UUID lessonId = UUID.randomUUID();
        when(loadLesson.findById(lessonId))
                .thenReturn(Optional.of(lesson(lessonId, UUID.randomUUID(), "남의 과정 차시", 1)));

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), lessonId, "제목", null, "VIDEO", "v1", true, "admin"))
                .isInstanceOf(LessonNotInCourseException.class);
        verify(saveLesson, never()).save(any());
    }

    @Test
    void deleteRejectsLessonThatBelongsToAnotherCourse() {
        UUID lessonId = UUID.randomUUID();
        when(loadLesson.findById(lessonId))
                .thenReturn(Optional.of(lesson(lessonId, UUID.randomUUID(), "남의 과정 차시", 1)));

        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), lessonId, "admin"))
                .isInstanceOf(LessonNotInCourseException.class);
        verify(deleteLesson, never()).deleteById(any());
    }

    @Test
    void deletingMissingLessonStaysIdempotent() {
        // 소속 대조를 넣으면서 "없는 차시 삭제"까지 실패로 바꾸면, 재시도가 안전하지 않게 된다.
        UUID lessonId = UUID.randomUUID();
        when(loadLesson.findById(lessonId)).thenReturn(Optional.empty());

        service.delete(UUID.randomUUID(), lessonId, "admin");

        verify(deleteLesson).deleteById(lessonId);
    }

    @Test
    void reorderUpdatesEveryLessonSequenceInRequestedOrder() {
        UUID courseId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(loadLesson.findByCourseOrderedBySequence(courseId)).thenReturn(List.of(
                lesson(first, courseId, "첫 차시", 1),
                lesson(second, courseId, "둘째 차시", 2)));

        service.reorder(courseId, List.of(second, first), "admin");

        // 1단계는 음수 구간(유니크 제약 회피), 2단계가 최종 순서다.
        ArgumentCaptor<Lesson> captor = ArgumentCaptor.forClass(Lesson.class);
        verify(saveLesson, times(4)).save(captor.capture());
        List<Lesson> saved = captor.getAllValues();
        assertThat(saved.get(2).id()).isEqualTo(second);
        assertThat(saved.get(2).sequence()).isEqualTo(1);
        assertThat(saved.get(3).id()).isEqualTo(first);
        assertThat(saved.get(3).sequence()).isEqualTo(2);
    }

    @Test
    void reorderRejectsRequestThatDoesNotCoverEveryLesson() {
        UUID courseId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        when(loadLesson.findByCourseOrderedBySequence(courseId))
                .thenReturn(List.of(lesson(first, courseId, "첫 차시", 1)));

        assertThatThrownBy(() -> service.reorder(courseId, List.of(first, UUID.randomUUID()), "admin"))
                .isInstanceOf(LessonOrderViolationException.class);
        verify(saveLesson, never()).save(any());
    }
}
