package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductImagePersistenceAdapterTest {

    @Mock SpringDataProductImageRepository repository;
    private final ProductImageMapper mapper = new ProductImageMapper();

    private ProductImagePersistenceAdapter adapter() {
        return new ProductImagePersistenceAdapter(repository, mapper);
    }

    private ProductImageJpaEntity entity(Long id, Long productId) {
        return new ProductImageJpaEntity(id, productId, "a.jpg", "s.jpg", "/p", "/u",
                "image/jpeg", 1024L, 100, 100, "chk", false, 0,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("findByIdNotDeleted: 존재하면 도메인으로 매핑")
    void findByIdNotDeleted_found() {
        when(repository.findByIdNotDeleted(1L)).thenReturn(Optional.of(entity(1L, 10L)));

        Optional<ProductImage> result = adapter().findByIdNotDeleted(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findByIdNotDeleted: 미존재면 empty")
    void findByIdNotDeleted_notFound() {
        when(repository.findByIdNotDeleted(1L)).thenReturn(Optional.empty());

        assertThat(adapter().findByIdNotDeleted(1L)).isEmpty();
    }

    @Test
    @DisplayName("findByProductIdNotDeleted: 리스트 매핑")
    void findByProductIdNotDeleted() {
        when(repository.findByProductIdNotDeleted(10L))
                .thenReturn(List.of(entity(1L, 10L), entity(2L, 10L)));

        List<ProductImage> result = adapter().findByProductIdNotDeleted(10L);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findPrimaryImageByProductId: 존재하면 매핑")
    void findPrimaryImageByProductId() {
        when(repository.findPrimaryImageByProductId(10L)).thenReturn(Optional.of(entity(1L, 10L)));

        assertThat(adapter().findPrimaryImageByProductId(10L)).isPresent();
    }

    @Test
    @DisplayName("countByProductIdNotDeleted: 카운트 위임")
    void countByProductIdNotDeleted() {
        when(repository.countByProductIdNotDeleted(10L)).thenReturn(3L);

        assertThat(adapter().countByProductIdNotDeleted(10L)).isEqualTo(3L);
    }

    @Test
    @DisplayName("save: 저장 후 도메인으로 매핑되어 반환")
    void save() {
        when(repository.save(any(ProductImageJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductImage image = ProductImage.create(10L, "a.jpg", "s.jpg", "/p", "/u",
                "image/jpeg", 1024L, 100, 100, 0);

        ProductImage saved = adapter().save(image);

        assertThat(saved.getProductId()).isEqualTo(10L);
        assertThat(saved.getUrl()).isEqualTo("/u");
    }
}
