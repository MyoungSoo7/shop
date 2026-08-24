package github.lms.lemuel.review.domain;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 리뷰 도메인 엔티티 (순수 POJO, 프레임워크 의존성 없음)
 */
public class Review {

    private Long id;
    private Long productId;
    private Long userId;
    private int rating;      // 1 ~ 5
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 노출 상태. 신고·욕설 대응은 삭제가 아니라 블라인드로 한다({@link ReviewStatus}). */
    private ReviewStatus status = ReviewStatus.VISIBLE;
    private String hiddenReason;
    private Long hiddenBy;
    private LocalDateTime hiddenAt;

    public Review() {}

    public static Review create(Long productId, Long userId, int rating, String content) {
        validateRating(rating);
        Review review = new Review();
        review.productId = productId;
        review.userId    = userId;
        review.rating    = rating;
        review.content   = content;
        review.createdAt = LocalDateTime.now();
        review.updatedAt = LocalDateTime.now();
        return review;
    }

    public void update(int rating, String content) {
        validateRating(rating);
        this.rating    = rating;
        this.content   = content;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 블라인드 처리 — 원문은 남기고 노출만 끊는다.
     *
     * <p>이미 숨겨진 리뷰를 다시 숨기면 <b>아무것도 바꾸지 않는다</b>. 여러 운영자가 같은 신고를
     * 보고 동시에 누르는 일은 흔한데, 그때마다 사유·시각·조작자를 덮으면 <b>최초 판단의 근거가
     * 지워진다</b>. 이의 제기에 답해야 하는 것은 처음 내린 결정이다.
     *
     * @param reason  숨기는 사유(필수) — 없으면 작성자에게도, 감사에도 설명할 수 없다
     * @param actorId 조작한 관리자
     */
    public void hide(String reason, Long actorId) {
        if (reason == null || reason.isBlank()) {
            throw new ReviewInvariantViolationException("블라인드 사유는 필수입니다.");
        }
        if (status == ReviewStatus.HIDDEN) {
            return;
        }
        this.status = ReviewStatus.HIDDEN;
        this.hiddenReason = reason.trim();
        this.hiddenBy = actorId;
        this.hiddenAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 블라인드 해제 — 다시 공개한다.
     *
     * <p>공개 상태에서 다시 부르면 no-op 이다. 해제하면 숨김 근거를 지우는데, 판단이 바뀐
     * 뒤의 옛 사유를 남겨 두면 "지금 왜 보이는가"에 대해 거짓 신호가 된다. 조작 이력 자체는
     * 감사 로그가 보존한다.
     */
    public void restore(Long actorId) {
        if (status == ReviewStatus.VISIBLE) {
            return;
        }
        this.status = ReviewStatus.VISIBLE;
        this.hiddenReason = null;
        this.hiddenBy = null;
        this.hiddenAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** 공개 목록에 나갈 수 있는가. 공개 조회 경로는 이 판단을 도메인에 묻는다. */
    public boolean isVisible() {
        return status == ReviewStatus.VISIBLE;
    }

    private static void validateRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new ReviewInvariantViolationException("평점은 1점에서 5점 사이여야 합니다.");
        }
    }

    /**
     * 영속 레코드 복원 팩토리 — no-arg + setter 대신 이 경로로만 도메인을 재구성한다. 평점 규칙은 유지 검증.
     */
    public static Review rehydrate(Long id, Long productId, Long userId, int rating, String content,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        return rehydrate(id, productId, userId, rating, content, createdAt, updatedAt,
                ReviewStatus.VISIBLE, null, null, null);
    }

    /**
     * 노출 상태까지 포함한 복원 팩토리.
     *
     * <p>{@code status} 가 null 이면 VISIBLE 로 본다 — 블라인드 컬럼이 생기기 전에 쌓인 행은
     * 전부 공개 상태였고, null 을 숨김으로 읽으면 과거 리뷰가 통째로 사라진다.
     */
    public static Review rehydrate(Long id, Long productId, Long userId, int rating, String content,
                                   LocalDateTime createdAt, LocalDateTime updatedAt,
                                   ReviewStatus status, String hiddenReason, Long hiddenBy,
                                   LocalDateTime hiddenAt) {
        validateRating(rating);
        Review review = new Review();
        review.id        = id;
        review.productId = productId;
        review.userId    = userId;
        review.rating    = rating;
        review.content   = content;
        review.createdAt = createdAt;
        review.updatedAt = updatedAt;
        review.status       = status != null ? status : ReviewStatus.VISIBLE;
        review.hiddenReason = hiddenReason;
        review.hiddenBy     = hiddenBy;
        review.hiddenAt     = hiddenAt;
        return review;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id)            { this.id = id; }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId()                      { return id; }

    public Long getProductId()               { return productId; }

    public Long getUserId()                  { return userId; }

    public int getRating()                   { return rating; }

    public String getContent()               { return content; }

    public LocalDateTime getCreatedAt()      { return createdAt; }

    public LocalDateTime getUpdatedAt()      { return updatedAt; }

    public ReviewStatus getStatus()          { return status; }

    public String getHiddenReason()          { return hiddenReason; }

    public Long getHiddenBy()                { return hiddenBy; }

    public LocalDateTime getHiddenAt()       { return hiddenAt; }
}