package github.lms.lemuel.review.application;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;

import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.domain.Review;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock SaveReviewPort saveReviewPort;
    @Mock LoadReviewPort loadReviewPort;
    @InjectMocks ReviewService service;

    @Test @DisplayName("createReview: 성공")
    void createReview_success() {
        when(loadReviewPort.existsByUserIdAndProductId(1L, 10L)).thenReturn(false);
        when(saveReviewPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Review result = service.createReview(10L, 1L, 5, "좋아요");
        assertThat(result.getRating()).isEqualTo(5);
    }

    @Test @DisplayName("createReview: 이미 리뷰 작성했으면 예외")
    void createReview_duplicate() {
        when(loadReviewPort.existsByUserIdAndProductId(1L, 10L)).thenReturn(true);
        assertThatThrownBy(() -> service.createReview(10L, 1L, 5, "좋아요"))
                .isInstanceOf(ReviewInvariantViolationException.class)
                .hasMessageContaining("이미 해당 상품");
    }

    @Test @DisplayName("updateReview: 성공")
    void updateReview_success() {
        Review review = Review.create(10L, 1L, 3, "보통");
        when(loadReviewPort.findById(1L)).thenReturn(Optional.of(review));
        when(saveReviewPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Review result = service.updateReview(1L, 1L, 5, "좋아요");
        assertThat(result.getRating()).isEqualTo(5);
    }

    @Test @DisplayName("updateReview: 본인이 아니면 예외")
    void updateReview_notOwner() {
        Review review = Review.create(10L, 1L, 3, "보통");
        when(loadReviewPort.findById(1L)).thenReturn(Optional.of(review));
        assertThatThrownBy(() -> service.updateReview(1L, 99L, 5, "좋아요"))
                .isInstanceOf(ReviewInvariantViolationException.class)
                .hasMessageContaining("본인이 작성한");
    }

    @Test @DisplayName("updateReview: 리뷰 없으면 예외")
    void updateReview_notFound() {
        when(loadReviewPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateReview(1L, 1L, 5, "좋아요"))
                .isInstanceOf(ReviewInvariantViolationException.class);
    }

    @Test @DisplayName("deleteReview: 성공")
    void deleteReview_success() {
        Review review = Review.create(10L, 1L, 3, "보통");
        when(loadReviewPort.findById(1L)).thenReturn(Optional.of(review));
        service.deleteReview(1L, 1L);
        verify(saveReviewPort).deleteById(1L);
    }

    @Test @DisplayName("deleteReview: 본인이 아니면 예외")
    void deleteReview_notOwner() {
        Review review = Review.create(10L, 1L, 3, "보통");
        when(loadReviewPort.findById(1L)).thenReturn(Optional.of(review));
        assertThatThrownBy(() -> service.deleteReview(1L, 99L))
                .isInstanceOf(ReviewInvariantViolationException.class);
    }

    @Test @DisplayName("getProductReviews: 조회")
    void getProductReviews() {
        when(loadReviewPort.findByProductId(10L)).thenReturn(List.of());
        assertThat(service.getProductReviews(10L)).isEmpty();
    }

    @Test @DisplayName("getProductReviews: 블라인드된 리뷰는 공개 목록에서 빠진다 — 이 필터가 없으면 블라인드 기능 전체가 무의미하다")
    void getProductReviewsExcludesHidden() {
        github.lms.lemuel.review.domain.Review visible = github.lms.lemuel.review.domain.Review.create(10L, 1L, 5, "좋아요");
        github.lms.lemuel.review.domain.Review hidden = github.lms.lemuel.review.domain.Review.create(10L, 2L, 1, "욕설");
        hidden.hide("신고 접수", 9L);
        when(loadReviewPort.findByProductId(10L)).thenReturn(List.of(visible, hidden));

        assertThat(service.getProductReviews(10L))
                .extracting(github.lms.lemuel.review.domain.Review::getContent)
                .containsExactly("좋아요");
    }

    @Test @DisplayName("getUserReviews: 조회")
    void getUserReviews() {
        when(loadReviewPort.findByUserId(1L)).thenReturn(List.of());
        assertThat(service.getUserReviews(1L)).isEmpty();
    }

    @Test @DisplayName("getUserReviews: 본인에게는 블라인드된 자기 글도 보인다 — 사라진 이유를 모르는 것이 가장 나쁜 경험이다")
    void getUserReviewsKeepsHiddenForAuthor() {
        github.lms.lemuel.review.domain.Review hidden = github.lms.lemuel.review.domain.Review.create(10L, 1L, 1, "욕설");
        hidden.hide("신고 접수", 9L);
        when(loadReviewPort.findByUserId(1L)).thenReturn(List.of(hidden));

        assertThat(service.getUserReviews(1L)).hasSize(1);
    }
}
