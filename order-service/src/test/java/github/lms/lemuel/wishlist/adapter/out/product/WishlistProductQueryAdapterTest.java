package github.lms.lemuel.wishlist.adapter.out.product;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductImage;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.wishlist.domain.WishlistAvailability;
import github.lms.lemuel.wishlist.domain.WishlistProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("찜 ↔ 상품 번역 어댑터")
class WishlistProductQueryAdapterTest {

    @Mock private LoadProductPort loadProductPort;
    @Mock private LoadProductImagePort loadProductImagePort;

    @InjectMocks private WishlistProductQueryAdapter adapter;

    private static Product product(long id, ProductStatus status, Integer stock) {
        return Product.rehydrate(id, "상품 " + id, "설명", new BigDecimal("1000"),
                stock, status, null, List.of(), null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @ParameterizedTest(name = "{0} + 재고 {1} → {2}")
    @CsvSource({
            "ACTIVE,        5,  AVAILABLE",
            "ACTIVE,        0,  OUT_OF_STOCK",   // 상태는 판매중인데 재고가 없다 — 실제로 못 산다
            "OUT_OF_STOCK,  5,  OUT_OF_STOCK",   // 재고가 남아도 상태가 품절이면 품절이다
            "INACTIVE,      5,  NOT_SELLING",
            "DISCONTINUED,  5,  DISCONTINUED",
            "DISCONTINUED,  0,  DISCONTINUED",   // 단종을 재고보다 먼저 본다
    })
    @DisplayName("상품 상태·재고를 찜이 보여 줄 사유로 번역한다")
    void translatesStatusAndStock(ProductStatus status, Integer stock, WishlistAvailability expected) {
        when(loadProductPort.findAllByIds(any())).thenReturn(List.of(product(10L, status, stock)));
        when(loadProductImagePort.findPrimaryImagesByProductIds(any())).thenReturn(Map.of());

        Map<Long, WishlistProduct> result = adapter.findAllByIds(List.of(10L));

        assertThat(result.get(10L).availability()).isEqualTo(expected);
    }

    @Test
    @DisplayName("단종된 재고 0 은 '품절'이 아니다 — 재입고를 기다리게 만들면 안 된다")
    void discontinuedBeatsOutOfStock() {
        when(loadProductPort.findAllByIds(any()))
                .thenReturn(List.of(product(10L, ProductStatus.DISCONTINUED, 0)));
        when(loadProductImagePort.findPrimaryImagesByProductIds(any())).thenReturn(Map.of());

        assertThat(adapter.findAllByIds(List.of(10L)).get(10L).availability())
                .isEqualTo(WishlistAvailability.DISCONTINUED);
    }

    @Test
    @DisplayName("재고가 null 이어도 터지지 않고 품절로 읽는다")
    void nullStockIsOutOfStock() {
        when(loadProductPort.findAllByIds(any()))
                .thenReturn(List.of(product(10L, ProductStatus.ACTIVE, null)));
        when(loadProductImagePort.findPrimaryImagesByProductIds(any())).thenReturn(Map.of());

        assertThat(adapter.findAllByIds(List.of(10L)).get(10L).availability())
                .isEqualTo(WishlistAvailability.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("상품이 몇 개든 상품 조회 1번 + 이미지 조회 1번이다 (N+1 방지)")
    void queriesOncePerConcern() {
        when(loadProductPort.findAllByIds(any())).thenReturn(List.of(
                product(10L, ProductStatus.ACTIVE, 5),
                product(11L, ProductStatus.ACTIVE, 5),
                product(12L, ProductStatus.ACTIVE, 5)));
        when(loadProductImagePort.findPrimaryImagesByProductIds(any())).thenReturn(Map.of());

        adapter.findAllByIds(List.of(10L, 11L, 12L));

        verify(loadProductPort, times(1)).findAllByIds(any());
        verify(loadProductImagePort, times(1)).findPrimaryImagesByProductIds(any());
    }

    @Test
    @DisplayName("대표 이미지가 있으면 URL 을 붙이고, 없으면 null 로 둔다")
    void attachesPrimaryImageWhenPresent() {
        when(loadProductPort.findAllByIds(any())).thenReturn(List.of(
                product(10L, ProductStatus.ACTIVE, 5),
                product(11L, ProductStatus.ACTIVE, 5)));
        ProductImage image = ProductImage.create(10L, "a.png", "s.png", "/p", "https://img/10",
                "image/png", 100L, 10, 10, 0);
        when(loadProductImagePort.findPrimaryImagesByProductIds(any())).thenReturn(Map.of(10L, image));

        Map<Long, WishlistProduct> result = adapter.findAllByIds(List.of(10L, 11L));

        assertThat(result.get(10L).primaryImageUrl()).isEqualTo("https://img/10");
        assertThat(result.get(11L).primaryImageUrl()).isNull();
    }

    @Test
    @DisplayName("조회되지 않은 상품은 결과에 없다 — 삭제 판정은 여기서 하지 않는다")
    void missingProductsAreSimplyAbsent() {
        when(loadProductPort.findAllByIds(any()))
                .thenReturn(List.of(product(10L, ProductStatus.ACTIVE, 5)));
        when(loadProductImagePort.findPrimaryImagesByProductIds(any())).thenReturn(Map.of());

        Map<Long, WishlistProduct> result = adapter.findAllByIds(List.of(10L, 99L));

        assertThat(result).containsOnlyKeys(10L);
    }

    @Test
    @DisplayName("빈 id 목록이면 DB 를 건드리지 않는다")
    void emptyIdsSkipsQueries() {
        Map<Long, WishlistProduct> result = adapter.findAllByIds(List.of());

        assertThat(result).isEmpty();
        verify(loadProductPort, never()).findAllByIds(any());
        verify(loadProductImagePort, never()).findPrimaryImagesByProductIds(any());
    }
}
