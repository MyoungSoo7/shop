package github.lms.lemuel.review.adapter.out.persistence;

import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.review.domain.ReviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ReviewPersistenceAdapter implements SaveReviewPort, LoadReviewPort {

    private final SpringDataReviewJpaRepository repository;

    // ── SaveReviewPort ─────────────────────────────────────────────────

    @Override
    public Review save(Review review) {
        ReviewJpaEntity entity = toEntity(review);
        ReviewJpaEntity saved  = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void deleteById(Long reviewId) {
        repository.deleteById(reviewId);
    }

    // ── LoadReviewPort ─────────────────────────────────────────────────

    @Override
    public Optional<Review> findById(Long reviewId) {
        return repository.findById(reviewId).map(this::toDomain);
    }

    @Override
    public List<Review> findByProductId(Long productId) {
        return repository.findByProductIdOrderByCreatedAtDesc(productId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Review> findByUserId(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<Review> findByUserIdAndProductId(Long userId, Long productId) {
        return repository.findByUserIdAndProductId(userId, productId).map(this::toDomain);
    }

    @Override
    public boolean existsByUserIdAndProductId(Long userId, Long productId) {
        return repository.existsByUserIdAndProductId(userId, productId);
    }

    // ── Mapper ─────────────────────────────────────────────────────────

    private ReviewJpaEntity toEntity(Review domain) {
        ReviewJpaEntity entity = new ReviewJpaEntity();
        entity.setId(domain.getId());
        entity.setProductId(domain.getProductId());
        entity.setUserId(domain.getUserId());
        entity.setRating((short) domain.getRating());
        entity.setContent(domain.getContent());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setStatus(domain.getStatus().name());
        entity.setHiddenReason(domain.getHiddenReason());
        entity.setHiddenBy(domain.getHiddenBy());
        entity.setHiddenAt(domain.getHiddenAt());
        return entity;
    }

    private Review toDomain(ReviewJpaEntity entity) {
        return Review.rehydrate(
                entity.getId(),
                entity.getProductId(),
                entity.getUserId(),
                entity.getRating(), // rehydrate validates rating
                entity.getContent(),
                entity.getCreatedAt(), // restore actual DB timestamps
                entity.getUpdatedAt(),
                toStatus(entity.getStatus()),
                entity.getHiddenReason(),
                entity.getHiddenBy(),
                entity.getHiddenAt());
    }

    /**
     * 컬럼 값을 노출 상태로 옮긴다.
     *
     * <p>null·미지의 문자열은 {@code VISIBLE} 로 읽는다. 블라인드 컬럼이 생기기 전에 쌓인 행은
     * 전부 공개였고, 모르는 값을 숨김으로 읽으면 과거 리뷰가 한꺼번에 사라진다 — 안전한 쪽은
     * "보여 준다"가 아니라 "원래 상태를 유지한다"이며, 원래 상태가 공개다.
     */
    private static ReviewStatus toStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReviewStatus.VISIBLE;
        }
        try {
            return ReviewStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ReviewStatus.VISIBLE;
        }
    }
}
