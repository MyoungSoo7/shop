package github.lms.lemuel.review.application.port.out;

import github.lms.lemuel.review.domain.Review;

public interface SaveReviewPort {
    Review save(Review review);
    void deleteById(Long reviewId);
}