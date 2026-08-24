package github.lms.lemuel.review.domain;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class ReviewTest {

    @Test @DisplayName("리뷰 생성")
    void create() {
        Review review = Review.create(1L, 2L, 5, "좋은 상품입니다");
        assertThat(review.getProductId()).isEqualTo(1L);
        assertThat(review.getUserId()).isEqualTo(2L);
        assertThat(review.getContent()).isEqualTo("좋은 상품입니다");
        assertThat(review.getRating()).isEqualTo(5);
    }

    @Test @DisplayName("평점 1-5 범위 검증")
    void invalidRating() {
        assertThatThrownBy(() -> Review.create(1L, 2L, 0, "내용"))
                .isInstanceOf(ReviewInvariantViolationException.class);
        assertThatThrownBy(() -> Review.create(1L, 2L, 6, "내용"))
                .isInstanceOf(ReviewInvariantViolationException.class);
    }

    @Test @DisplayName("리뷰 수정")
    void update() {
        Review review = Review.create(1L, 2L, 3, "원래 내용");
        review.update(4, "수정된 내용");
        assertThat(review.getContent()).isEqualTo("수정된 내용");
        assertThat(review.getRating()).isEqualTo(4);
    }
}
