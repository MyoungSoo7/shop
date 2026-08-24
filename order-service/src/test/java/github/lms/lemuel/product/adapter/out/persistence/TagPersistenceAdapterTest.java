package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagPersistenceAdapterTest {

    @Mock SpringDataTagRepository tagRepository;
    @Mock SpringDataProductTagRepository productTagRepository;
    private final TagMapper tagMapper = new TagMapper();

    private TagPersistenceAdapter adapter() {
        return new TagPersistenceAdapter(tagRepository, productTagRepository, tagMapper);
    }

    private TagJpaEntity entity(Long id, String name) {
        return new TagJpaEntity(id, name, "#FF0000", LocalDateTime.now());
    }

    @Test
    @DisplayName("save: 매핑 후 저장하고 도메인 복원")
    void save() {
        Tag tag = Tag.create("신상", "#FF0000");
        when(tagRepository.save(any(TagJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Tag saved = adapter().save(tag);

        assertThat(saved.getName()).isEqualTo("신상");
    }

    @Test
    @DisplayName("delete: repository 위임")
    void delete() {
        adapter().delete(1L);

        verify(tagRepository).deleteById(1L);
    }

    @Test
    @DisplayName("addTagToProduct: ProductTag 저장")
    void addTagToProduct() {
        adapter().addTagToProduct(10L, 1L);

        verify(productTagRepository).save(any(ProductTagJpaEntity.class));
    }

    @Test
    @DisplayName("removeTagFromProduct: repository 위임")
    void removeTagFromProduct() {
        adapter().removeTagFromProduct(10L, 1L);

        verify(productTagRepository).deleteByProductIdAndTagId(10L, 1L);
    }

    @Test
    @DisplayName("removeAllTagsFromProduct: repository 위임")
    void removeAllTagsFromProduct() {
        adapter().removeAllTagsFromProduct(10L);

        verify(productTagRepository).deleteByProductId(10L);
    }

    @Test
    @DisplayName("findById: 존재하면 매핑")
    void findById_found() {
        when(tagRepository.findById(1L)).thenReturn(Optional.of(entity(1L, "신상")));

        assertThat(adapter().findById(1L)).isPresent();
    }

    @Test
    @DisplayName("findById: 미존재면 empty")
    void findById_notFound() {
        when(tagRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(adapter().findById(1L)).isEmpty();
    }

    @Test
    @DisplayName("findByName: 위임")
    void findByName() {
        when(tagRepository.findByName("신상")).thenReturn(Optional.of(entity(1L, "신상")));

        assertThat(adapter().findByName("신상")).isPresent();
    }

    @Test
    @DisplayName("findAll: 리스트 매핑")
    void findAll() {
        when(tagRepository.findAll()).thenReturn(List.of(entity(1L, "신상"), entity(2L, "베스트")));

        assertThat(adapter().findAll()).hasSize(2);
    }

    @Test
    @DisplayName("findByProductId: 위임")
    void findByProductId() {
        when(tagRepository.findByProductId(10L)).thenReturn(List.of(entity(1L, "신상")));

        assertThat(adapter().findByProductId(10L)).hasSize(1);
    }
}
