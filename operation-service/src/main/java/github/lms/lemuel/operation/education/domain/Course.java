package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;

import java.time.Instant;
import java.util.UUID;

/**
 * 과정 애그리거트 루트 — 상태머신(DRAFT/PUBLISHED/HIDDEN/CLOSED)의 단일 진실원.
 *
 * <p>이전에는 같은 전이 규칙이 {@code CourseJpaEntity} 에도 복제돼 있었고 애플리케이션 서비스가
 * 그쪽을 썼다(도메인 모델은 만들어만 두고 아무도 부르지 않는 이중 모델). 지금은 영속 엔티티가
 * 매핑만 담당하고 규칙은 여기에만 있다.
 *
 * <p>{@code version} 은 영속 계층의 낙관적 락 값을 실어 나르기만 한다 — 애그리거트가 자기 버전을
 * 알아야 재적재 없이 저장·이벤트 발행이 가능하기 때문이며, 도메인이 증가시키지는 않는다.
 */
public final class Course {
    private final UUID id;
    private String title;
    private String description;
    private CourseStatus status;
    private Instant publishedAt;
    private Instant closedAt;
    private String updatedBy;
    private final long version;

    private Course(UUID id, String title, String description, CourseStatus status,
                   Instant publishedAt, Instant closedAt, String updatedBy, long version) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = status;
        this.publishedAt = publishedAt;
        this.closedAt = closedAt;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static Course draft(UUID id, String title, String description, String actor) {
        return new Course(id, title, description, CourseStatus.DRAFT, null, null, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점(전이 규칙을 우회하지 않는다). */
    public static Course rehydrate(UUID id, String title, String description, CourseStatus status,
                                   Instant publishedAt, Instant closedAt, String updatedBy, long version) {
        return new Course(id, title, description, status, publishedAt, closedAt, updatedBy, version);
    }

    public void update(String title, String description, String actor) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        this.title = title;
        this.description = description;
        this.updatedBy = actor;
    }

    public void publish(String actor) {
        require(CourseStatus.DRAFT, CourseStatus.HIDDEN);
        status = CourseStatus.PUBLISHED;
        publishedAt = Instant.now();
        updatedBy = actor;
    }

    public void hide(String actor) {
        require(CourseStatus.PUBLISHED);
        status = CourseStatus.HIDDEN;
        updatedBy = actor;
    }

    public void close(String actor) {
        require(CourseStatus.PUBLISHED, CourseStatus.HIDDEN);
        status = CourseStatus.CLOSED;
        closedAt = Instant.now();
        updatedBy = actor;
    }

    private void require(CourseStatus... allowed) {
        for (CourseStatus candidate : allowed) if (status == candidate) return;
        throw new InvalidCourseStateException("course cannot transition from " + status);
    }

    public UUID id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    public CourseStatus status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public Instant closedAt() { return closedAt; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
