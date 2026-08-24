package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.out.LoadTagPort;
import github.lms.lemuel.product.application.port.out.SaveTagPort;
import github.lms.lemuel.product.domain.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock SaveTagPort saveTagPort;
    @Mock LoadTagPort loadTagPort;
    @InjectMocks TagService service;

    @Test @DisplayName("createTag: 태그 생성")
    void createTag() {
        when(saveTagPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Tag result = service.createTag("신상품", "#FF0000");
        assertThat(result.getName()).isEqualTo("신상품");
    }

    @Test @DisplayName("getTagById: 조회 성공")
    void getById_success() {
        Tag tag = Tag.create("태그", "#000000");
        when(loadTagPort.findById(1L)).thenReturn(Optional.of(tag));
        assertThat(service.getTagById(1L)).isSameAs(tag);
    }

    @Test @DisplayName("getTagById: 없으면 예외")
    void getById_notFound() {
        when(loadTagPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getTagById(1L))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("getAllTags: 전체 태그 조회")
    void getAll() {
        when(loadTagPort.findAll()).thenReturn(List.of());
        assertThat(service.getAllTags()).isEmpty();
    }

    @Test @DisplayName("getTagsByProductId: 상품별 태그")
    void getByProductId() {
        when(loadTagPort.findByProductId(1L)).thenReturn(List.of());
        assertThat(service.getTagsByProductId(1L)).isEmpty();
    }

    @Test @DisplayName("updateTag: 태그 수정")
    void updateTag() {
        Tag tag = Tag.create("원본", "#000000");
        when(loadTagPort.findById(1L)).thenReturn(Optional.of(tag));
        when(saveTagPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Tag result = service.updateTag(1L, "수정됨", "#FFFFFF");
        assertThat(result.getName()).isEqualTo("수정됨");
    }

    @Test @DisplayName("deleteTag: 태그 삭제")
    void deleteTag() {
        service.deleteTag(1L);
        verify(saveTagPort).delete(1L);
    }

    @Test @DisplayName("addTagToProduct: 상품에 태그 추가")
    void addTagToProduct() {
        Tag tag = Tag.create("태그", "#000000");
        when(loadTagPort.findById(1L)).thenReturn(Optional.of(tag));
        service.addTagToProduct(10L, 1L);
        verify(saveTagPort).addTagToProduct(10L, 1L);
    }

    @Test @DisplayName("removeTagFromProduct: 상품에서 태그 제거")
    void removeTagFromProduct() {
        service.removeTagFromProduct(10L, 1L);
        verify(saveTagPort).removeTagFromProduct(10L, 1L);
    }
}
