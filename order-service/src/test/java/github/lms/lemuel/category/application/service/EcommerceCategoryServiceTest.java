package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.out.LoadEcommerceCategoryPort;
import github.lms.lemuel.category.application.port.out.SaveEcommerceCategoryPort;
import github.lms.lemuel.category.domain.EcommerceCategory;
import github.lms.lemuel.category.domain.exception.CategoryHasChildrenException;
import github.lms.lemuel.category.domain.exception.CategoryHasProductsException;
import github.lms.lemuel.category.domain.exception.CategoryNotFoundException;
import github.lms.lemuel.category.domain.exception.CircularReferenceException;
import github.lms.lemuel.category.domain.exception.DuplicateSlugException;
import github.lms.lemuel.category.util.SlugGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcommerceCategoryServiceTest {

    @Mock LoadEcommerceCategoryPort loadPort;
    @Mock SaveEcommerceCategoryPort savePort;
    @Mock SlugGenerator slugGenerator;
    @InjectMocks EcommerceCategoryService service;

    private EcommerceCategory root(Long id, String slug) {
        EcommerceCategory c = EcommerceCategory.createRoot("cat-" + id, slug, 0);
        c.assignId(id);
        return c;
    }

    @Test @DisplayName("createCategory - root, slug 자동 생성")
    void createRoot_autoSlug() {
        when(slugGenerator.generate("전자제품")).thenReturn("electronics");
        when(loadPort.findBySlug("electronics")).thenReturn(Optional.empty());
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EcommerceCategory result = service.createCategory("전자제품", null, null, 0);

        assertThat(result.getSlug()).isEqualTo("electronics");
        assertThat(result.isRoot()).isTrue();
    }

    @Test @DisplayName("createCategory - child, 부모 slug 결합")
    void createChild_autoSlug() {
        when(loadPort.findByIdNotDeleted(10L)).thenReturn(Optional.of(root(10L, "electronics")));
        when(slugGenerator.generateWithParent("electronics", "노트북")).thenReturn("electronics-laptop");
        when(loadPort.findBySlug("electronics-laptop")).thenReturn(Optional.empty());
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EcommerceCategory result = service.createCategory("노트북", null, 10L, 1);

        assertThat(result.getSlug()).isEqualTo("electronics-laptop");
        assertThat(result.getParentId()).isEqualTo(10L);
        assertThat(result.getDepth()).isEqualTo(1);
    }

    @Test @DisplayName("createCategory - 중복 slug 시 DuplicateSlugException")
    void createCategory_duplicateSlug() {
        when(loadPort.findBySlug("dup")).thenReturn(Optional.of(root(99L, "dup")));

        assertThatThrownBy(() -> service.createCategory("x", "dup", null, 0))
                .isInstanceOf(DuplicateSlugException.class);
    }

    @Test @DisplayName("getCategoryById - 미존재 시 CategoryNotFoundException")
    void getById_notFound() {
        when(loadPort.findByIdNotDeleted(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getCategoryById(999L))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test @DisplayName("getCategoryBySlug - soft deleted 면 제외")
    void getBySlug_deleted() {
        EcommerceCategory deleted = root(1L, "x");
        deleted.softDelete();
        when(loadPort.findBySlug("x")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.getCategoryBySlug("x"))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test @DisplayName("getAllCategoriesTree - root-child 관계로 트리 구성")
    void tree_buildsHierarchy() {
        EcommerceCategory r = root(1L, "r");
        EcommerceCategory c = EcommerceCategory.createChild("c", "r-c", 1L, 0, 0);
        c.assignId(2L);
        when(loadPort.findAllNotDeleted()).thenReturn(List.of(r, c));

        List<EcommerceCategory> tree = service.getAllCategoriesTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getChildren()).hasSize(1);
        assertThat(tree.get(0).getChildren().get(0).getId()).isEqualTo(2L);
    }

    @Test @DisplayName("updateCategory - slug 변경 시 중복이면 예외")
    void update_duplicateSlug() {
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(root(1L, "old")));
        when(loadPort.findBySlug("new-slug")).thenReturn(Optional.of(root(99L, "new-slug")));

        assertThatThrownBy(() -> service.updateCategory(1L, "x", "new-slug"))
                .isInstanceOf(DuplicateSlugException.class);
    }

    @Test @DisplayName("moveCategory - 자신의 자손을 부모로 지정하면 순환 예외")
    void move_circular() {
        EcommerceCategory r = root(1L, "r");
        EcommerceCategory c = EcommerceCategory.createChild("c", "r-c", 1L, 0, 0);
        c.assignId(2L);
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(r));
        // getDescendantIds 가 전체를 1회 로드해 BFS 하므로 findAllNotDeleted 로 스텁
        when(loadPort.findAllNotDeleted()).thenReturn(List.of(r, c));

        assertThatThrownBy(() -> service.moveCategory(1L, 2L))
                .isInstanceOf(CircularReferenceException.class);
    }

    @Test @DisplayName("moveCategory - 손자(전이적 자손)를 부모로 지정해도 순환 예외 (BFS 다단계 순회)")
    void move_circular_transitive() {
        // 1 → 2 → 3 체인. 1 을 자신의 손자 3 밑으로 옮기면 순환.
        EcommerceCategory r = root(1L, "r");
        EcommerceCategory c2 = EcommerceCategory.createChild("c2", "r-c2", 1L, 0, 0);
        c2.assignId(2L);
        EcommerceCategory c3 = EcommerceCategory.createChild("c3", "r-c2-c3", 2L, 1, 0);
        c3.assignId(3L);
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(r));
        when(loadPort.findAllNotDeleted()).thenReturn(List.of(r, c2, c3));

        // getDescendantIds(1) 가 BFS 로 2 → 3 까지 도달해야 순환을 잡아낸다
        assertThatThrownBy(() -> service.moveCategory(1L, 3L))
                .isInstanceOf(CircularReferenceException.class);
    }

    @Test @DisplayName("deleteCategory - 자식 존재 시 예외")
    void delete_hasChildren() {
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(root(1L, "r")));
        when(loadPort.countChildrenByParentId(1L)).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteCategory(1L))
                .isInstanceOf(CategoryHasChildrenException.class);
    }

    @Test @DisplayName("deleteCategory - 연결된 상품 존재 시 예외")
    void delete_hasProducts() {
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(root(1L, "r")));
        when(loadPort.countChildrenByParentId(1L)).thenReturn(0L);
        when(loadPort.hasProducts(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteCategory(1L))
                .isInstanceOf(CategoryHasProductsException.class);
    }

    @Test @DisplayName("deleteCategory - 정상 경로: softDelete 호출")
    void delete_success() {
        EcommerceCategory target = root(1L, "r");
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(target));
        when(loadPort.countChildrenByParentId(1L)).thenReturn(0L);
        when(loadPort.hasProducts(1L)).thenReturn(false);
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteCategory(1L);

        assertThat(target.isDeleted()).isTrue();
    }

    @Test @DisplayName("activateCategory / deactivateCategory")
    void toggleActive() {
        EcommerceCategory c = root(1L, "r");
        c.deactivate();
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(c));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EcommerceCategory activated = service.activateCategory(1L);
        assertThat(activated.getIsActive()).isTrue();

        EcommerceCategory deactivated = service.deactivateCategory(1L);
        assertThat(deactivated.getIsActive()).isFalse();
    }

    @Test @DisplayName("changeSortOrder - 값 반영")
    void changeSortOrder() {
        EcommerceCategory c = root(1L, "r");
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(c));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EcommerceCategory updated = service.changeSortOrder(1L, 5);

        assertThat(updated.getSortOrder()).isEqualTo(5);
    }
}
