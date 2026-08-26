package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.CourseCapacityExceededException;
import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import org.junit.jupiter.api.DisplayName;
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
                publishedAt, null, 30, "editor", 5L);

        assertThat(course.id()).isEqualTo(id);
        assertThat(course.status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(course.publishedAt()).isEqualTo(publishedAt);
        assertThat(course.closedAt()).isNull();
        assertThat(course.capacity()).isEqualTo(30);
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

    @Test
    @DisplayName("정원 없음(null)과 정원 0 은 다르다 — null 은 무제한, 0 은 마감이다")
    void nullCapacityMeansUnlimited() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");

        assertThat(course.capacity()).isNull();
        // 무제한이면 몇 명이 확정돼 있든 한 자리 더 들어간다.
        course.ensureSeatAvailable(9999);

        course.changeCapacity(0, 0, "admin");
        assertThatThrownBy(() -> course.ensureSeatAvailable(0))
                .isInstanceOf(CourseCapacityExceededException.class);
    }

    @Test
    @DisplayName("정원이 차면 한 자리 더 확정하지 못한다")
    void fullCourseRejectsOneMoreSeat() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.changeCapacity(2, 0, "admin");

        course.ensureSeatAvailable(1);

        assertThatThrownBy(() -> course.ensureSeatAvailable(2))
                .isInstanceOf(CourseCapacityExceededException.class);
    }

    @Test
    @DisplayName("확정 인원보다 작게 정원을 줄이지 못한다 — 누가 자리를 잃는지 아무도 정하지 않았다")
    void capacityCannotDropBelowConfirmed() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.changeCapacity(10, 0, "admin");

        assertThatThrownBy(() -> course.changeCapacity(2, 3, "admin"))
                .isInstanceOf(CourseCapacityExceededException.class);
        // 거절됐으면 정원도 그대로여야 한다 — 반쯤 줄어든 상태가 남으면 안 된다.
        assertThat(course.capacity()).isEqualTo(10);

        // 확정 인원과 같은 수까지는 줄일 수 있다 — 더 받지만 않으면 되는 상태다.
        course.changeCapacity(3, 3, "admin");
        assertThat(course.capacity()).isEqualTo(3);
    }

    @Test
    @DisplayName("음수 정원은 받지 않는다")
    void negativeCapacityIsRejected() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        assertThatThrownBy(() -> course.changeCapacity(-1, 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정원을 다시 없앨 수 있다 — null 로 되돌리는 경로")
    void capacityCanBeCleared() {
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.changeCapacity(5, 0, "admin");

        course.changeCapacity(null, 5, "admin");

        assertThat(course.capacity()).isNull();
        course.ensureSeatAvailable(100);
    }
}
