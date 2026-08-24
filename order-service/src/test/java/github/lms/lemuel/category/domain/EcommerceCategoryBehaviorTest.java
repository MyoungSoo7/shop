package github.lms.lemuel.category.domain;
import github.lms.lemuel.category.domain.exception.InvalidCategoryStateException;
import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EcommerceCategory 도메인 비즈니스 메서드 보완 테스트 —
 * changeParent/changeSortOrder/activate/deactivate/softDelete/updateInfo/상태확인 경로를 커버한다.
 */
class EcommerceCategoryBehaviorTest {

    private EcommerceCategory root() {
        EcommerceCategory c = EcommerceCategory.createRoot("전자제품", "electronics", 1);
        c.assignId(1L);
        return c;
    }

    @Test
    @DisplayName("createChild: 부모 depth+1 로 생성, 최대 depth 초과 시 예외")
    void createChild() {
        EcommerceCategory child = EcommerceCategory.createChild("노트북", "laptop", 1L, 0, 2);
        assertThat(child.getDepth()).isEqualTo(1);
        assertThat(child.getParentId()).isEqualTo(1L);

        assertThatThrownBy(() -> EcommerceCategory.createChild("과함", "toodeep", 5L, 2, 1))
                .isInstanceOf(InvalidCategoryStateException.class);
    }

    @Test
    @DisplayName("changeParent: 루트로 이동하면 depth 0")
    void changeParent_toRoot() {
        EcommerceCategory c = EcommerceCategory.createChild("노트북", "laptop", 1L, 0, 1);
        c.changeParent(null, null);
        assertThat(c.getParentId()).isNull();
        assertThat(c.getDepth()).isZero();
    }

    @Test
    @DisplayName("changeParent: 자기 자신을 부모로 지정하면 예외")
    void changeParent_self() {
        EcommerceCategory c = root();
        assertThatThrownBy(() -> c.changeParent(1L, 0))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("changeParent: 이동 결과 depth 초과면 예외")
    void changeParent_tooDeep() {
        EcommerceCategory c = root();
        assertThatThrownBy(() -> c.changeParent(9L, EcommerceCategory.MAX_DEPTH))
                .isInstanceOf(InvalidCategoryStateException.class);
    }

    @Test
    @DisplayName("changeSortOrder: 음수면 예외")
    void changeSortOrder() {
        EcommerceCategory c = root();
        c.changeSortOrder(5);
        assertThat(c.getSortOrder()).isEqualTo(5);
        assertThatThrownBy(() -> c.changeSortOrder(-1))
                .isInstanceOf(CategoryInvariantViolationException.class);
        assertThatThrownBy(() -> c.changeSortOrder(null))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("activate/deactivate/softDelete + 상태 확인")
    void lifecycle() {
        EcommerceCategory c = root();
        c.deactivate();
        assertThat(c.getIsActive()).isFalse();
        c.activate();
        assertThat(c.getIsActive()).isTrue();

        assertThat(c.isRoot()).isTrue();
        assertThat(c.isDeleted()).isFalse();
        assertThat(c.canHaveChildren()).isTrue();

        c.softDelete();
        assertThat(c.isDeleted()).isTrue();
        assertThat(c.getIsActive()).isFalse();
        assertThatThrownBy(c::activate).isInstanceOf(InvalidCategoryStateException.class);
    }

    @Test
    @DisplayName("updateInfo: 이름/slug 갱신, 공백은 무시")
    void updateInfo() {
        EcommerceCategory c = root();
        c.updateInfo("가전", "home-appliance");
        assertThat(c.getName()).isEqualTo("가전");
        assertThat(c.getSlug()).isEqualTo("home-appliance");

        c.updateInfo("  ", "  "); // 공백은 무시 → 기존 값 유지
        assertThat(c.getName()).isEqualTo("가전");
        assertThat(c.getSlug()).isEqualTo("home-appliance");
    }

    @Test
    @DisplayName("canHaveChildren: 최대 depth 에서는 false")
    void canHaveChildren_atMaxDepth() {
        EcommerceCategory leaf = EcommerceCategory.createChild("리프", "leaf", 2L,
                EcommerceCategory.MAX_DEPTH - 1, 1);
        assertThat(leaf.canHaveChildren()).isFalse();
    }
}
