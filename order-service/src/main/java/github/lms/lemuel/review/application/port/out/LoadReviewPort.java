package github.lms.lemuel.review.application.port.out;

import github.lms.lemuel.review.domain.Review;

import java.util.List;
import java.util.Optional;

public interface LoadReviewPort {
    Optional<Review> findById(Long reviewId);
    List<Review> findByProductId(Long productId);
    List<Review> findByUserId(Long userId);
    Optional<Review> findByUserIdAndProductId(Long userId, Long productId);
    boolean existsByUserIdAndProductId(Long userId, Long productId);
}