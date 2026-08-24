package github.lms.lemuel.review.application;

import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {

    private final SaveReviewPort saveReviewPort;
    private final LoadReviewPort loadReviewPort;

    /** 리뷰 작성 */
    public Review createReview(Long productId, Long userId, int rating, String content) {
        log.info("리뷰 작성 시작: productId={}, userId={}, rating={}", productId, userId, rating);

        if (loadReviewPort.existsByUserIdAndProductId(userId, productId)) {
            throw new ReviewInvariantViolationException("이미 해당 상품에 리뷰를 작성하셨습니다.");
        }

        Review review = Review.create(productId, userId, rating, content);
        try {
            Review saved = saveReviewPort.save(review);
            log.info("리뷰 작성 완료: reviewId={}", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new ReviewInvariantViolationException("이미 해당 상품에 리뷰를 작성하셨습니다.");
        }
    }

    /** 리뷰 수정 */
    public Review updateReview(Long reviewId, Long userId, int rating, String content) {
        log.info("리뷰 수정 시작: reviewId={}, userId={}", reviewId, userId);

        Review review = loadReviewPort.findById(reviewId)
                .orElseThrow(() -> new ReviewInvariantViolationException("리뷰를 찾을 수 없습니다. id=" + reviewId));

        if (!review.getUserId().equals(userId)) {
            throw new ReviewInvariantViolationException("본인이 작성한 리뷰만 수정할 수 있습니다.");
        }

        review.update(rating, content);
        Review updated = saveReviewPort.save(review);
        log.info("리뷰 수정 완료: reviewId={}", reviewId);
        return updated;
    }

    /** 리뷰 삭제 */
    public void deleteReview(Long reviewId, Long userId) {
        log.info("리뷰 삭제 시작: reviewId={}, userId={}", reviewId, userId);

        Review review = loadReviewPort.findById(reviewId)
                .orElseThrow(() -> new ReviewInvariantViolationException("리뷰를 찾을 수 없습니다. id=" + reviewId));

        if (!review.getUserId().equals(userId)) {
            throw new ReviewInvariantViolationException("본인이 작성한 리뷰만 삭제할 수 있습니다.");
        }

        saveReviewPort.deleteById(reviewId);
        log.info("리뷰 삭제 완료: reviewId={}", reviewId);
    }

    /**
     * 상품 리뷰 목록 (최신순).
     *
     * <p><b>블라인드된 리뷰는 여기서 빠진다.</b> 이 필터가 없으면 관리자가 신고 리뷰를 숨겨도
     * 상품 상세에는 그대로 보인다 — 블라인드 기능 전체가 아무 일도 하지 않는 셈이 된다.
     * 노출을 끊는 지점은 <b>공개 조회 경로</b>이고, 그 판단은 도메인에게 묻는다.
     */
    @Transactional(readOnly = true)
    public List<Review> getProductReviews(Long productId) {
        return loadReviewPort.findByProductId(productId).stream()
                .filter(Review::isVisible)
                .toList();
    }

    /**
     * 사용자 리뷰 목록.
     *
     * <p>여기서는 <b>거르지 않는다</b>. 자기 글이 왜 사라졌는지 모르는 것이 가장 나쁜 경험이고,
     * 숨김 사유를 볼 수 있어야 이의 제기가 성립한다.
     */
    @Transactional(readOnly = true)
    public List<Review> getUserReviews(Long userId) {
        return loadReviewPort.findByUserId(userId);
    }
}