package github.lms.lemuel.review.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataReviewJpaRepository extends JpaRepository<ReviewJpaEntity, Long> {

    List<ReviewJpaEntity> findByProductIdOrderByCreatedAtDesc(Long productId);

    List<ReviewJpaEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ReviewJpaEntity> findByUserIdAndProductId(Long userId, Long productId);

    boolean existsByUserIdAndProductId(Long userId, Long productId);
}