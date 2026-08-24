package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductVariantPersistenceAdapterTest {

    @Mock SpringDataProductVariantRepository repository;

    private ProductVariantPersistenceAdapter adapter() {
        return new ProductVariantPersistenceAdapter(repository);
    }

    private ProductVariantJpaEntity entity(Long id) {
        return new ProductVariantJpaEntity(id, 1L, "SKU-1", "색상:빨강",
                new BigDecimal("500"), 10, 0L, ProductVariantStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("loadById: 존재하면 도메인 복원")
    void loadById_found() {
        when(repository.findById(1L)).thenReturn(Optional.of(entity(1L)));

        Optional<ProductVariant> result = adapter().loadById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getSku()).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName("loadById: 미존재면 empty")
    void loadById_notFound() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThat(adapter().loadById(1L)).isEmpty();
    }

    @Test
    @DisplayName("loadBySku: 위임")
    void loadBySku() {
        when(repository.findBySku("SKU-1")).thenReturn(Optional.of(entity(1L)));

        assertThat(adapter().loadBySku("SKU-1")).isPresent();
    }

    @Test
    @DisplayName("loadByProductId: 리스트 매핑")
    void loadByProductId() {
        when(repository.findByProductId(1L)).thenReturn(List.of(entity(1L), entity(2L)));

        assertThat(adapter().loadByProductId(1L)).hasSize(2);
    }

    @Test
    @DisplayName("save: 신규(id 없음) 는 INSERT 엔티티 생성 후 저장")
    void save_new() {
        ProductVariant variant = ProductVariant.create(1L, "SKU-NEW", "사이즈:L",
                new BigDecimal("1000"), 5);
        when(repository.save(any(ProductVariantJpaEntity.class)))
                .thenAnswer(inv -> {
                    ProductVariantJpaEntity e = inv.getArgument(0);
                    return new ProductVariantJpaEntity(100L, e.getProductId(), e.getSku(), e.getOptionName(),
                            e.getAdditionalPrice(), e.getDiscountPrice(), e.getDiscountRate(),
                            e.getStockQuantity(), e.getVersion(), e.getStatus(),
                            e.getCreatedAt(), e.getUpdatedAt());
                });

        ProductVariant saved = adapter().save(variant);

        assertThat(saved.getId()).isEqualTo(100L);
        assertThat(saved.getSku()).isEqualTo("SKU-NEW");
    }

    @Test
    @DisplayName("save: 기존(id 있음) 은 findById 후 도메인 상태를 반영해 저장")
    void save_existing() {
        ProductVariant existing = ProductVariant.rehydrate(1L, 1L, "SKU-1", "색상:빨강",
                new BigDecimal("500"), 10, 0L, ProductVariantStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
        existing.decreaseStock(3);
        ProductVariantJpaEntity found = entity(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(found));
        when(repository.save(found)).thenReturn(found);

        ProductVariant saved = adapter().save(existing);

        assertThat(saved.getStockQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("save: 기존(id 있음) 인데 엔티티가 사라졌으면 예외")
    void save_existingMissing() {
        ProductVariant existing = ProductVariant.rehydrate(1L, 1L, "SKU-1", "색상:빨강",
                new BigDecimal("500"), 10, 0L, ProductVariantStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().save(existing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사라짐");
    }

    @Test
    @DisplayName("decreaseStockIfAvailable: repository 위임")
    void decreaseStockIfAvailable() {
        when(repository.decreaseStockIfAvailable(eq(1L), eq(3), any())).thenReturn(1);

        assertThat(adapter().decreaseStockIfAvailable(1L, 3)).isEqualTo(1);
    }

    @Test
    @DisplayName("increaseStock: repository 위임")
    void increaseStock() {
        when(repository.increaseStock(eq(1L), eq(3), any())).thenReturn(1);

        assertThat(adapter().increaseStock(1L, 3)).isEqualTo(1);
    }
}
