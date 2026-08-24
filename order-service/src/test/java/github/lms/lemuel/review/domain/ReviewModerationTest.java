package github.lms.lemuel.review.domain;

import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리뷰 블라인드 도메인 규칙.
 *
 * <p>운영 조작이지만 규칙은 도메인이 갖는다 — 사유 없는 블라인드, 최초 판단 근거의 덮어쓰기,
 * 옛 컬럼 없는 행의 오해석은 전부 "나중에 설명할 수 없는 상태"로 이어진다.
 */
class ReviewModerationTest {

    private static Review visibleReview() {
        return Review.create(1L, 2L, 5, "좋아요");
    }

    @Test
    @DisplayName("새 리뷰는 공개 상태다")
    void newReviewIsVisible() {
        Review review = visibleReview();

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(review.isVisible()).isTrue();
        assertThat(review.getHiddenReason()).isNull();
    }

    @Test
    @DisplayName("블라인드하면 원문은 남고 노출만 끊긴다 — 삭제가 아니다")
    void hideKeepsContent() {
        Review review = visibleReview();

        review.hide("욕설 신고 접수", 9L);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.HIDDEN);
        assertThat(review.isVisible()).isFalse();
        assertThat(review.getContent()).isEqualTo("좋아요");
        assertThat(review.getHiddenReason()).isEqualTo("욕설 신고 접수");
        assertThat(review.getHiddenBy()).isEqualTo(9L);
        assertThat(review.getHiddenAt()).isNotNull();
    }

    @Test
    @DisplayName("사유 없는 블라인드는 거부한다 — 작성자에게도 감사에도 설명할 수 없다")
    void hideRequiresReason() {
        Review review = visibleReview();

        assertThatThrownBy(() -> review.hide("   ", 9L))
                .isInstanceOf(ReviewInvariantViolationException.class)
                .hasMessageContaining("사유");
        assertThatThrownBy(() -> review.hide(null, 9L))
                .isInstanceOf(ReviewInvariantViolationException.class);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
    }

    @Test
    @DisplayName("이미 숨긴 리뷰를 다시 숨겨도 최초 판단 근거를 덮지 않는다")
    void hideIsIdempotentAndPreservesFirstDecision() {
        Review review = visibleReview();
        review.hide("욕설 신고 접수", 9L);
        LocalDateTime firstHiddenAt = review.getHiddenAt();

        review.hide("다른 운영자가 같은 신고를 보고 누름", 11L);

        assertThat(review.getHiddenReason()).isEqualTo("욕설 신고 접수");
        assertThat(review.getHiddenBy()).isEqualTo(9L);
        assertThat(review.getHiddenAt()).isEqualTo(firstHiddenAt);
    }

    @Test
    @DisplayName("해제하면 숨김 근거를 지운다 — 판단이 바뀐 뒤의 옛 사유는 거짓 신호다")
    void restoreClearsHiddenTrace() {
        Review review = visibleReview();
        review.hide("오판", 9L);

        review.restore(11L);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(review.getHiddenReason()).isNull();
        assertThat(review.getHiddenBy()).isNull();
        assertThat(review.getHiddenAt()).isNull();
    }

    @Test
    @DisplayName("공개 상태에서 해제를 다시 불러도 아무 일도 없다")
    void restoreIsIdempotent() {
        Review review = visibleReview();

        review.restore(11L);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
    }

    @Test
    @DisplayName("블라인드 컬럼이 없던 옛 행(status=null)은 공개로 복원한다 — 숨김으로 읽으면 과거 리뷰가 통째로 사라진다")
    void nullStatusRehydratesAsVisible() {
        Review review = Review.rehydrate(1L, 2L, 3L, 4, "옛 리뷰",
                LocalDateTime.of(2025, 1, 1, 0, 0), LocalDateTime.of(2025, 1, 1, 0, 0),
                null, null, null, null);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(review.isVisible()).isTrue();
    }

    @Test
    @DisplayName("기존 7-인자 복원 팩토리는 공개 상태를 뜻한다 — 호출부를 바꾸지 않아도 안전하다")
    void legacyRehydrateStaysVisible() {
        Review review = Review.rehydrate(1L, 2L, 3L, 4, "옛 리뷰",
                LocalDateTime.of(2025, 1, 1, 0, 0), LocalDateTime.of(2025, 1, 1, 0, 0));

        assertThat(review.isVisible()).isTrue();
    }

    @Test
    @DisplayName("숨긴 리뷰도 작성자는 내용을 수정할 수 있고, 수정한다고 다시 공개되지는 않는다")
    void editingHiddenReviewKeepsItHidden() {
        Review review = visibleReview();
        review.hide("욕설", 9L);

        review.update(3, "수정했습니다");

        assertThat(review.getContent()).isEqualTo("수정했습니다");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.HIDDEN);
    }
}
