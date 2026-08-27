package github.lms.lemuel.wishlist.adapter.out.persistence;

import github.lms.lemuel.wishlist.domain.WishlistItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 찜 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속). */
@ExtendWith(MockitoExtension.class)
@DisplayName("찜 영속 어댑터")
class WishlistPersistenceAdapterTest {

    @Mock SpringDataWishlistItemRepository repository;
    @InjectMocks WishlistPersistenceAdapter adapter;

    private static WishlistItemJpaEntity entity(Long id, Long userId, Long productId) {
        return new WishlistItemJpaEntity(id, userId, productId, LocalDateTime.now());
    }

    @Test
    @DisplayName("목록은 담은 시각 역순으로 읽어 도메인으로 매핑한다")
    void loadsNewestFirst() {
        when(repository.findByUserIdOrderByAddedAtDesc(7L))
                .thenReturn(List.of(entity(1L, 7L, 10L), entity(2L, 7L, 11L)));

        List<WishlistItem> items = adapter.findByUserId(7L);

        assertThat(items).extracting(WishlistItem::productId).containsExactly(10L, 11L);
        assertThat(items.get(0).id()).isEqualTo(1L);
        assertThat(items.get(0).userId()).isEqualTo(7L);
        assertThat(items.get(0).addedAt()).isNotNull();
    }

    @Test
    @DisplayName("유니크 제약 위반을 삼키지 않고 그대로 올린다")
    void doesNotSwallowUniqueViolation() {
        // 여기서 잡아 "성공"으로 바꾸면 무엇이 멱등이고 무엇이 진짜 오류인지 판단할
        // 정보가 서비스에 닿지 않는다. 판단하는 자리는 서비스다.
        when(repository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_wishlist_items_user_product"));

        assertThatThrownBy(() -> adapter.save(WishlistItem.add(7L, 10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("저장한 뒤 DB 가 발급한 id 를 도메인에 실어 돌려준다")
    void returnsDatabaseAssignedId() {
        when(repository.save(any())).thenReturn(entity(42L, 7L, 10L));

        WishlistItem saved = adapter.save(WishlistItem.add(7L, 10L));

        assertThat(saved.id()).isEqualTo(42L);
        ArgumentCaptor<WishlistItemJpaEntity> captor =
                ArgumentCaptor.forClass(WishlistItemJpaEntity.class);
        verify(repository).save(captor.capture());
        // 앱이 id 를 계산해 넣지 않는다 — 레거시의 MAX(id)+1 이 만들던 경합이 여기서 사라진다.
        assertThat(captor.getValue().getId()).isNull();
    }

    @Test
    @DisplayName("빼기는 실제로 지워졌는지를 돌려준다")
    void deleteReportsWhetherAnythingWasRemoved() {
        when(repository.deleteByUserIdAndProductId(7L, 10L)).thenReturn(1L);
        assertThat(adapter.deleteByUserIdAndProductId(7L, 10L)).isTrue();

        when(repository.deleteByUserIdAndProductId(7L, 11L)).thenReturn(0L);
        assertThat(adapter.deleteByUserIdAndProductId(7L, 11L)).isFalse();
    }

    @Test
    @DisplayName("일괄 삭제는 지운 행 수를 돌려준다")
    void bulkDeleteReturnsCount() {
        when(repository.deleteByUserIdAndProductIdIn(7L, List.of(10L, 11L))).thenReturn(2L);

        assertThat(adapter.deleteByUserIdAndProductIds(7L, List.of(10L, 11L))).isEqualTo(2);
    }

    @Test
    @DisplayName("지울 것이 없으면 쿼리를 보내지 않는다 (빈 IN 절 회피)")
    void emptyBulkDeleteSkipsQuery() {
        assertThat(adapter.deleteByUserIdAndProductIds(7L, List.of())).isZero();

        verify(repository, never()).deleteByUserIdAndProductIdIn(anyLong(), anyCollection());
    }

    @Test
    @DisplayName("존재 확인·개수는 저장소에 그대로 위임한다")
    void delegatesExistsAndCount() {
        when(repository.existsByUserIdAndProductId(7L, 10L)).thenReturn(true);
        when(repository.countByUserId(7L)).thenReturn(3L);

        assertThat(adapter.exists(7L, 10L)).isTrue();
        assertThat(adapter.countByUserId(7L)).isEqualTo(3L);
    }
}
