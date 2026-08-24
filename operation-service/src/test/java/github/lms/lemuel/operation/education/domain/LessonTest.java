package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.LessonOrderViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LessonTest {

    @Test
    void reorderAcceptsEveryLessonExactlyOnce() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(Lesson.validateReorder(List.of(first, second), List.of(second, first))).isTrue();
    }

    @Test
    void reorderRejectsForeignLesson() {
        UUID first = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();

        assertThatThrownBy(() -> Lesson.validateReorder(List.of(first), List.of(first, foreign)))
                .isInstanceOf(LessonOrderViolationException.class);
    }

    @Test
    void reorderRejectsDuplicatedExistingIds() {
        UUID duplicated = UUID.randomUUID();

        assertThatThrownBy(() -> Lesson.validateReorder(List.of(duplicated, duplicated), List.of(duplicated, duplicated)))
                .isInstanceOf(LessonOrderViolationException.class);
    }

    @Test
    void createdLessonIsActiveAndCarriesItsContent() {
        UUID id = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();

        Lesson lesson = Lesson.create(id, courseId, "1차시", "설명", 1, "VIDEO", "v1", true, "admin");

        assertThat(lesson.id()).isEqualTo(id);
        assertThat(lesson.courseId()).isEqualTo(courseId);
        assertThat(lesson.title()).isEqualTo("1차시");
        assertThat(lesson.description()).isEqualTo("설명");
        assertThat(lesson.sequence()).isEqualTo(1);
        assertThat(lesson.contentType()).isEqualTo(LessonContentType.VIDEO);
        assertThat(lesson.contentRef()).isEqualTo("v1");
        assertThat(lesson.required()).isTrue();
        assertThat(lesson.status()).isEqualTo(LessonStatus.ACTIVE);
        assertThat(lesson.updatedBy()).isEqualTo("admin");
        assertThat(lesson.version()).isZero();
    }

    @Test
    void updateReplacesContentAndRecordsActor() {
        Lesson lesson = Lesson.create(UUID.randomUUID(), UUID.randomUUID(), "1차시", "설명", 1, "VIDEO", "v1", true, "admin");

        lesson.update("바뀐 차시", "새 설명", "DOCUMENT", "d1", false, "editor");

        assertThat(lesson.title()).isEqualTo("바뀐 차시");
        assertThat(lesson.description()).isEqualTo("새 설명");
        assertThat(lesson.contentType()).isEqualTo(LessonContentType.DOCUMENT);
        assertThat(lesson.contentRef()).isEqualTo("d1");
        assertThat(lesson.required()).isFalse();
        assertThat(lesson.updatedBy()).isEqualTo("editor");
    }

    @Test
    void blankTitleIsRejectedOnCreateAndUpdate() {
        UUID courseId = UUID.randomUUID();

        assertThatThrownBy(() -> Lesson.create(UUID.randomUUID(), courseId, " ", null, 1, "VIDEO", "v1", true, "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        Lesson lesson = Lesson.create(UUID.randomUUID(), courseId, "1차시", null, 1, "VIDEO", "v1", true, "admin");
        assertThatThrownBy(() -> lesson.update(null, null, "VIDEO", "v1", true, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeSequenceAcceptsTheNegativeStagingRangeUsedByReorder() {
        Lesson lesson = Lesson.create(UUID.randomUUID(), UUID.randomUUID(), "1차시", null, 1, "VIDEO", "v1", true, "admin");

        lesson.changeSequence(-2, "editor");
        assertThat(lesson.sequence()).isEqualTo(-2);

        lesson.changeSequence(2, "editor");
        assertThat(lesson.sequence()).isEqualTo(2);
        assertThat(lesson.updatedBy()).isEqualTo("editor");
    }

    @Test
    void rehydrateRestoresPersistedState() {
        UUID id = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();

        Lesson lesson = Lesson.rehydrate(id, courseId, "1차시", "설명", 3, LessonContentType.EXTERNAL_LINK,
                "https://example.test", false, LessonStatus.HIDDEN, "editor", 7L);

        assertThat(lesson.sequence()).isEqualTo(3);
        assertThat(lesson.contentType()).isEqualTo(LessonContentType.EXTERNAL_LINK);
        assertThat(lesson.contentRef()).isEqualTo("https://example.test");
        assertThat(lesson.required()).isFalse();
        assertThat(lesson.status()).isEqualTo(LessonStatus.HIDDEN);
        assertThat(lesson.version()).isEqualTo(7L);
    }
}
