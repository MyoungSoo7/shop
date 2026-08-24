package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.LessonNotInCourseException;
import github.lms.lemuel.operation.education.domain.exception.LessonOrderViolationException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 차시 — 과정 애그리거트에 속하지만 재정렬·개별 수정 때문에 독립 식별자로 다룬다.
 *
 * <p>이전에는 {@code LessonJpaEntity} 가 상태와 규칙을 모두 들고 있었고 도메인 쪽은 재정렬
 * 검증용 정적 메서드만 있는 껍데기였다. 지금은 여기가 정본이고 영속 엔티티는 매핑만 한다.
 */
public final class Lesson {
    private final UUID id;
    private final UUID courseId;
    private String title;
    private String description;
    private int sequence;
    private LessonContentType contentType;
    private String contentRef;
    private boolean required;
    private final LessonStatus status;
    private String updatedBy;
    private final long version;

    private Lesson(UUID id, UUID courseId, String title, String description, int sequence,
                   LessonContentType contentType, String contentRef, boolean required,
                   LessonStatus status, String updatedBy, long version) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        this.id = id;
        this.courseId = courseId;
        this.title = title;
        this.description = description;
        this.sequence = sequence;
        this.contentType = contentType;
        this.contentRef = contentRef;
        this.required = required;
        this.status = status;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static Lesson create(UUID id, UUID courseId, String title, String description, int sequence,
                                String contentType, String contentRef, boolean required, String actor) {
        return new Lesson(id, courseId, title, description, sequence, LessonContentType.valueOf(contentType),
                contentRef, required, LessonStatus.ACTIVE, actor, 0L);
    }

    /** 영속 상태에서 되살린다 — 어댑터 전용 진입점. */
    public static Lesson rehydrate(UUID id, UUID courseId, String title, String description, int sequence,
                                   LessonContentType contentType, String contentRef, boolean required,
                                   LessonStatus status, String updatedBy, long version) {
        return new Lesson(id, courseId, title, description, sequence, contentType, contentRef, required,
                status, updatedBy, version);
    }

    public void update(String title, String description, String contentType, String contentRef,
                       boolean required, String actor) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        this.title = title;
        this.description = description;
        this.contentType = LessonContentType.valueOf(contentType);
        this.contentRef = contentRef;
        this.required = required;
        this.updatedBy = actor;
    }

    /**
     * 차시 순서를 옮긴다.
     *
     * <p>음수도 허용한다 — 재정렬은 {@code (course_id, sequence)} 유니크 제약 때문에 음수 구간을
     * 경유하는 2단 저장으로 수행된다(자세한 사유는 {@code LessonAdminService.reorder}).
     */
    public void changeSequence(int sequence, String actor) {
        this.sequence = sequence;
        this.updatedBy = actor;
    }

    /**
     * 이 차시가 주어진 과정에 속하는지 대조한다.
     *
     * <p>차시는 독립 식별자를 갖지만 <b>과정 애그리거트에 속한다</b>. 그래서 "차시 하나"만으로
     * 수정·삭제를 허용하면 URL 이 주장한 소속(`/courses/{courseId}/lessons/{lessonId}`)과 실제
     * 소속이 어긋나도 아무도 눈치채지 못한다. 대조는 어댑터가 아니라 여기에 둔다 — 진입점이
     * 늘어나도 규칙은 한 곳에 남는다.
     */
    public void requireBelongsTo(UUID expectedCourseId) {
        if (!courseId.equals(expectedCourseId)) {
            throw new LessonNotInCourseException(
                    "lesson " + id + " does not belong to course " + expectedCourseId);
        }
    }

    /** 요청 순서가 이 과정의 차시 전부를 정확히 한 번씩 담고 있는지 검증한다. */
    public static boolean validateReorder(List<UUID> existingIds, List<UUID> requestedIds) {
        if (existingIds.size() != requestedIds.size()
                || new HashSet<>(existingIds).size() != existingIds.size()
                || !new HashSet<>(existingIds).equals(new HashSet<>(requestedIds))) {
            throw new LessonOrderViolationException("lesson order must contain each course lesson exactly once");
        }
        return true;
    }

    public UUID id() { return id; }
    public UUID courseId() { return courseId; }
    public String title() { return title; }
    public String description() { return description; }
    public int sequence() { return sequence; }
    public LessonContentType contentType() { return contentType; }
    public String contentRef() { return contentRef; }
    public boolean required() { return required; }
    public LessonStatus status() { return status; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
