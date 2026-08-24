package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseTest {

    @Test
    void draftCanBePublishedAndHiddenThenClosed() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");

        course.publish("admin");
        assertThat(course.status()).isEqualTo(CourseStatus.PUBLISHED);

        course.hide("admin");
        assertThat(course.status()).isEqualTo(CourseStatus.HIDDEN);

        course.close("admin");
        assertThat(course.status()).isEqualTo(CourseStatus.CLOSED);
        assertThat(course.id()).isNotNull();
        assertThat(course.title()).isEqualTo("정산 교육");
        assertThat(course.description()).isEqualTo("설명");
        assertThat(course.publishedAt()).isNotNull();
        assertThat(course.closedAt()).isNotNull();
        assertThat(course.updatedBy()).isEqualTo("admin");
    }

    @Test
    void closedCourseCannotBePublishedAgain() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.publish("admin");
        course.hide("admin");
        course.close("admin");

        assertThatThrownBy(() -> course.publish("admin"))
                .isInstanceOf(InvalidCourseStateException.class);
    }

    @Test
    void updateReplacesTitleAndDescriptionAndRecordsActor() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");

        course.update("바뀐 교육", "새 설명", "editor");

        assertThat(course.title()).isEqualTo("바뀐 교육");
        assertThat(course.description()).isEqualTo("새 설명");
        assertThat(course.updatedBy()).isEqualTo("editor");
        assertThat(course.status()).isEqualTo(CourseStatus.DRAFT);
    }

    @Test
    void blankTitleIsRejectedOnDraftAndUpdate() {
        assertThatThrownBy(() -> Course.draft(UUID.randomUUID(), " ", "설명", "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        assertThatThrownBy(() -> course.update(null, "설명", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rehydrateRestoresPersistedStateIncludingVersion() {
        UUID id = UUID.randomUUID();
        Instant publishedAt = Instant.parse("2026-08-01T00:00:00Z");

        Course course = Course.rehydrate(id, "정산 교육", "설명", CourseStatus.PUBLISHED,
                publishedAt, null, "editor", 5L);

        assertThat(course.id()).isEqualTo(id);
        assertThat(course.status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(course.publishedAt()).isEqualTo(publishedAt);
        assertThat(course.closedAt()).isNull();
        assertThat(course.version()).isEqualTo(5L);
        // 되살린 애그리거트도 전이 규칙을 그대로 받는다 — 우회 경로가 아니다.
        assertThatThrownBy(() -> course.publish("admin")).isInstanceOf(InvalidCourseStateException.class);
    }

    @Test
    void draftCourseStartsAtVersionZero() {
        assertThat(Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin").version()).isZero();
    }

    @Test
    void invalidHideAndCloseTransitionsAreRejected() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        assertThatThrownBy(() -> course.hide("admin")).isInstanceOf(InvalidCourseStateException.class);
        assertThatThrownBy(() -> course.close("admin")).isInstanceOf(InvalidCourseStateException.class);
        course.publish("admin");
        assertThatThrownBy(() -> course.publish("admin")).isInstanceOf(InvalidCourseStateException.class);
    }
}
