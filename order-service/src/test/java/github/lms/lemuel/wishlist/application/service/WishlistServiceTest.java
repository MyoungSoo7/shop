package github.lms.lemuel.wishlist.application.service;

import github.lms.lemuel.wishlist.application.port.in.WishlistUseCase;
import github.lms.lemuel.wishlist.application.port.out.LoadWishlistPort;
import github.lms.lemuel.wishlist.application.port.out.LoadWishlistProductPort;
import github.lms.lemuel.wishlist.application.port.out.SaveWishlistPort;
import github.lms.lemuel.wishlist.domain.Wishlist;
import github.lms.lemuel.wishlist.domain.WishlistAvailability;
import github.lms.lemuel.wishlist.domain.WishlistEntry;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import github.lms.lemuel.wishlist.domain.WishlistProduct;
import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("찜 서비스")
class WishlistServiceTest {

    private static final Long USER_ID = 7L;

    @Mock private LoadWishlistPort loadWishlistPort;
    @Mock private SaveWishlistPort saveWishlistPort;
    @Mock private LoadWishlistProductPort loadProductPort;

    @InjectMocks private WishlistService service;

    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now();
    }

    private WishlistItem item(long id, long productId) {
        return WishlistItem.rehydrate(id, USER_ID, productId, now);
    }

    private WishlistProduct product(long productId, WishlistAvailability availability) {
        return new WishlistProduct(productId, "상품 " + productId,
                new BigDecimal("1000"), availability, null);
    }

    private Map<Long, WishlistProduct> productsOf(Map<Long, WishlistAvailability> spec) {
        Map<Long, WishlistProduct> map = new LinkedHashMap<>();
        spec.forEach((id, availability) -> map.put(id, product(id, availability)));
        return map;
    }

    // ---------------------------------------------------------------- list

    @Test
    @DisplayName("빈 목록이면 상품 조회를 아예 하지 않는다")
    void emptyListSkipsProductLookup() {
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of());

        Wishlist result = service.list(USER_ID);

        assertThat(result.isEmpty()).isTrue();
        verify(loadProductPort, never()).findAllByIds(any());
    }

    @Test
    @DisplayName("항목이 몇 개든 상품 조회는 한 번이다 (N+1 방지)")
    void hydratesProductsInOneCall() {
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of(
                item(1L, 10L), item(2L, 11L), item(3L, 12L)));
        when(loadProductPort.findAllByIds(any())).thenReturn(productsOf(Map.of(
                10L, WishlistAvailability.AVAILABLE,
                11L, WishlistAvailability.OUT_OF_STOCK,
                12L, WishlistAvailability.DISCONTINUED)));

        Wishlist result = service.list(USER_ID);

        assertThat(result.size()).isEqualTo(3);
        verify(loadProductPort, times(1)).findAllByIds(any());
    }

    @Test
    @DisplayName("상품이 사라진 찜 행도 목록에 남는다 — '삭제된 상품'으로")
    void missingProductBecomesRemovedEntry() {
        // 레거시는 이 줄을 조회에서 걸러 냈다. 사용자는 담아 둔 것이 말없이 사라지는 것을 봤고,
        // 개수 배지와 실제 목록도 어긋났다.
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of(item(1L, 10L), item(2L, 99L)));
        when(loadProductPort.findAllByIds(any()))
                .thenReturn(productsOf(Map.of(10L, WishlistAvailability.AVAILABLE)));

        Wishlist result = service.list(USER_ID);

        assertThat(result.size()).isEqualTo(2);
        WishlistEntry orphan = result.entries().stream()
                .filter(e -> e.productId().equals(99L)).findFirst().orElseThrow();
        assertThat(orphan.product().name()).isEqualTo(WishlistProduct.REMOVED_NAME);
        assertThat(orphan.isGone()).isTrue();
        assertThat(orphan.reason()).isEqualTo(WishlistAvailability.REMOVED.label());
    }

    @Test
    @DisplayName("같은 상품이 여러 줄이어도 상품 id 는 중복 없이 한 번만 묻는다")
    void deduplicatesProductIdsBeforeLookup() {
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of(item(1L, 10L), item(2L, 10L)));
        when(loadProductPort.findAllByIds(any()))
                .thenReturn(productsOf(Map.of(10L, WishlistAvailability.AVAILABLE)));

        service.list(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(loadProductPort).findAllByIds(captor.capture());
        assertThat(captor.getValue()).containsExactly(10L);
    }

    @Test
    @DisplayName("userId 가 없으면 조회 자체를 거부한다")
    void listRequiresUser() {
        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(WishlistInvariantViolationException.class);
    }

    // ----------------------------------------------------------------- add

    @Test
    @DisplayName("담기면 결과 상태와 늘어난 개수를 돌려준다")
    void addStoresAndReportsResultingState() {
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn(2L);
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(false);
        when(saveWishlistPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WishlistUseCase.Mutation result = service.add(USER_ID, 10L);

        assertThat(result.present()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(result.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("이미 담겨 있으면 아무것도 저장하지 않고 결과만 알린다")
    void addIsIdempotentWhenAlreadyPresent() {
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn(2L);
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(true);

        WishlistUseCase.Mutation result = service.add(USER_ID, 10L);

        assertThat(result.present()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.size()).isEqualTo(2);
        verify(saveWishlistPort, never()).save(any());
    }

    @Test
    @DisplayName("사전 조회를 통과한 뒤 유니크 제약이 거부해도 오류가 아니다 (더블탭)")
    void addTreatsUniqueViolationAsAlreadyPresent() {
        // 레거시에는 제약이 없어 이 경합이 곧 중복 행이었다. 여기서는 DB 가 막고,
        // 사용자가 원한 상태는 이미 성립했으므로 500 이 아니라 "이미 담김"으로 답한다.
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn(2L, 3L);
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(false);
        when(saveWishlistPort.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_wishlist_items_user_product"));

        WishlistUseCase.Mutation result = service.add(USER_ID, 10L);

        assertThat(result.present()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("상한에 닿으면 담기를 거부한다")
    void addRefusesBeyondMaxItems() {
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn((long) Wishlist.MAX_ITEMS);
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.add(USER_ID, 10L))
                .isInstanceOf(WishlistInvariantViolationException.class)
                .hasMessageContaining(String.valueOf(Wishlist.MAX_ITEMS));
        verify(saveWishlistPort, never()).save(any());
    }

    @Test
    @DisplayName("상한을 넘겨도 이미 담긴 상품은 다시 담을 수 있다 — 개수가 늘지 않는다")
    void addAllowsRepeatAtMaxWhenAlreadyPresent() {
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn((long) Wishlist.MAX_ITEMS);
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(true);

        WishlistUseCase.Mutation result = service.add(USER_ID, 10L);

        assertThat(result.present()).isTrue();
        assertThat(result.changed()).isFalse();
    }

    @Test
    @DisplayName("담을 때 저장되는 것은 주인·상품·시각뿐이다 (옵션·수량을 얼리지 않는다)")
    void addStoresOnlyOwnerProductAndTime() {
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn(0L);
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(false);
        when(saveWishlistPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.add(USER_ID, 10L);

        ArgumentCaptor<WishlistItem> captor = ArgumentCaptor.forClass(WishlistItem.class);
        verify(saveWishlistPort).save(captor.capture());
        WishlistItem saved = captor.getValue();
        assertThat(saved.id()).isNull();
        assertThat(saved.userId()).isEqualTo(USER_ID);
        assertThat(saved.productId()).isEqualTo(10L);
        assertThat(saved.addedAt()).isNotNull();
    }

    @Test
    @DisplayName("productId 가 없으면 거부한다")
    void addRequiresProduct() {
        assertThatThrownBy(() -> service.add(USER_ID, null))
                .isInstanceOf(WishlistInvariantViolationException.class);
    }

    // -------------------------------------------------------------- remove

    @Test
    @DisplayName("빼면 지워졌음과 남은 개수를 알린다")
    void removeReportsChangeAndRemainingCount() {
        when(saveWishlistPort.deleteByUserIdAndProductId(USER_ID, 10L)).thenReturn(true);
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn(1L);

        WishlistUseCase.Mutation result = service.remove(USER_ID, 10L);

        assertThat(result.present()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("원래 담겨 있지 않았어도 오류가 아니다")
    void removeIsIdempotent() {
        when(saveWishlistPort.deleteByUserIdAndProductId(USER_ID, 10L)).thenReturn(false);
        when(loadWishlistPort.countByUserId(USER_ID)).thenReturn(0L);

        WishlistUseCase.Mutation result = service.remove(USER_ID, 10L);

        assertThat(result.present()).isFalse();
        assertThat(result.changed()).isFalse();
    }

    // ------------------------------------------------------------ contains

    @Test
    @DisplayName("담김 여부는 단건으로 묻는다 — 목록을 읽지 않는다")
    void containsDoesNotLoadWholeList() {
        when(loadWishlistPort.exists(USER_ID, 10L)).thenReturn(true);

        assertThat(service.contains(USER_ID, 10L)).isTrue();
        verify(loadWishlistPort, never()).findByUserId(anyLong());
    }

    // ----------------------------------------------------------- purgeGone

    @Test
    @DisplayName("정리는 단종·삭제만 지운다 — 품절은 남긴다")
    void purgeRemovesOnlyGoneEntries() {
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of(
                item(1L, 10L),   // 구매 가능
                item(2L, 11L),   // 품절 — 남아야 한다
                item(3L, 12L),   // 단종
                item(4L, 99L))); // 상품 삭제
        when(loadProductPort.findAllByIds(any())).thenReturn(productsOf(Map.of(
                10L, WishlistAvailability.AVAILABLE,
                11L, WishlistAvailability.OUT_OF_STOCK,
                12L, WishlistAvailability.DISCONTINUED)));
        when(saveWishlistPort.deleteByUserIdAndProductIds(eq(USER_ID), any())).thenReturn(2);

        WishlistUseCase.PurgeResult result = service.purgeGone(USER_ID);

        assertThat(result.removed()).extracting(WishlistEntry::productId)
                .containsExactly(12L, 99L);
        assertThat(result.remaining().entries()).extracting(WishlistEntry::productId)
                .containsExactly(10L, 11L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(saveWishlistPort).deleteByUserIdAndProductIds(eq(USER_ID), captor.capture());
        assertThat(captor.getValue()).containsExactly(12L, 99L);
    }

    @Test
    @DisplayName("지울 것이 없으면 삭제를 호출하지 않는다")
    void purgeSkipsDeleteWhenNothingGone() {
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of(item(1L, 10L)));
        when(loadProductPort.findAllByIds(any()))
                .thenReturn(productsOf(Map.of(10L, WishlistAvailability.AVAILABLE)));

        WishlistUseCase.PurgeResult result = service.purgeGone(USER_ID);

        assertThat(result.removed()).isEmpty();
        assertThat(result.remaining().size()).isEqualTo(1);
        verify(saveWishlistPort, never()).deleteByUserIdAndProductIds(anyLong(), any());
    }

    @Test
    @DisplayName("빈 목록을 정리해도 아무 일도 일어나지 않는다")
    void purgeOnEmptyListIsNoop() {
        when(loadWishlistPort.findByUserId(USER_ID)).thenReturn(List.of());

        WishlistUseCase.PurgeResult result = service.purgeGone(USER_ID);

        assertThat(result.removed()).isEmpty();
        assertThat(result.remaining().isEmpty()).isTrue();
        verify(saveWishlistPort, never()).deleteByUserIdAndProductIds(anyLong(), any());
    }
}
