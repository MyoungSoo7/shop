package github.lms.lemuel.review.adapter.out.persistence;

import github.lms.lemuel.review.domain.Review;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 리뷰 영속 어댑터 매핑/위임 회귀 테스트 (Mockito, 실 DB 미접속).
 */
@ExtendWith(MockitoExtension.class)
class ReviewPersistenceAdapterTest {

    @Mock SpringDataReviewJpaRepository repository;
    @InjectMocks ReviewPersistenceAdapter adapter;

    private ReviewJpaEntity entity() {
        ReviewJpaEntity e = new ReviewJpaEntity();
        e.setId(7L);
        e.setProductId(100L);
        e.setUserId(200L);
        e.setRating((short) 4);
        e.setContent("좋아요");
        e.setCreatedAt(LocalDateTime.now().minusDays(1));
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("save: 도메인→엔티티→저장→도메인 왕복이 필드를 보존한다")
    void save() {
        when(repository.save(any(ReviewJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Review review = Review.create(100L, 200L, 5, "최고");
        Review saved = adapter.save(review);

        assertThat(saved.getProductId()).isEqualTo(100L);
        assertThat(saved.getUserId()).isEqualTo(200L);
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getContent()).isEqualTo("최고");
        verify(repository).save(any(ReviewJpaEntity.class));
    }

    @Test
    @DisplayName("deleteById: 리포지토리 위임")
    void deleteById() {
        adapter.deleteById(7L);
        verify(repository).deleteById(7L);
    }

    @Test
    @DisplayName("findById: 존재 시 도메인 매핑 + DB 타임스탬프 복원")
    void findById_present() {
        LocalDateTime created = LocalDateTime.now().minusDays(3);
        ReviewJpaEntity e = entity();
        e.setCreatedAt(created);
        when(repository.findById(7L)).thenReturn(Optional.of(e));

        Review review = adapter.findById(7L).orElseThrow();

        assertThat(review.getId()).isEqualTo(7L);
        assertThat(review.getRating()).isEqualTo(4);
        assertThat(review.getCreatedAt()).isEqualTo(created);
    }

    @Test
    @DisplayName("findById: 미존재 시 empty")
    void findById_empty() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThat(adapter.findById(99L)).isEmpty();
    }

    @Test
    @DisplayName("findByProductId: 상품별 정렬 조회 매핑")
    void findByProductId() {
        when(repository.findByProductIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of(entity()));
        assertThat(adapter.findByProductId(100L)).hasSize(1);
    }

    @Test
    @DisplayName("findByUserId: 사용자별 정렬 조회 매핑")
    void findByUserId() {
        when(repository.findByUserIdOrderByCreatedAtDesc(200L))
                .thenReturn(List.of(entity()));
        assertThat(adapter.findByUserId(200L)).hasSize(1);
    }

    @Test
    @DisplayName("findByUserIdAndProductId: 존재 시 매핑")
    void findByUserIdAndProductId() {
        when(repository.findByUserIdAndProductId(200L, 100L))
                .thenReturn(Optional.of(entity()));
        assertThat(adapter.findByUserIdAndProductId(200L, 100L)).isPresent();
    }

    @Test
    @DisplayName("existsByUserIdAndProductId: 리포지토리 위임")
    void existsByUserIdAndProductId() {
        when(repository.existsByUserIdAndProductId(200L, 100L)).thenReturn(true);
        assertThat(adapter.existsByUserIdAndProductId(200L, 100L)).isTrue();
    }
}
