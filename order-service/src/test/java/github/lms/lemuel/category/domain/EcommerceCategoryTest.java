package github.lms.lemuel.category.domain;
import github.lms.lemuel.category.domain.exception.InvalidCategoryStateException;
import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EcommerceCategoryTest {

    @Nested
    @DisplayName("createRoot — 최상위 카테고리 생성")
    class CreateRoot {

        @Test @DisplayName("정상 생성: depth=0, parentId=null, isRoot=true")
        void createRoot_valid() {
            EcommerceCategory c = EcommerceCategory.createRoot("전자제품", "electronics", 0);

            assertThat(c.getDepth()).isZero();
            assertThat(c.getParentId()).isNull();
            assertThat(c.getIsActive()).isTrue();
            assertThat(c.isRoot()).isTrue();
            assertThat(c.canHaveChildren()).isTrue();
        }

        @Test @DisplayName("name 이 비어있으면 예외")
        void createRoot_emptyName() {
            assertThatThrownBy(() -> EcommerceCategory.createRoot("  ", "electronics", 0))
                    .isInstanceOf(CategoryInvariantViolationException.class)
                    .hasMessageContaining("empty");
        }

        @Test @DisplayName("slug 가 소문자·숫자·하이픈 외 문자 포함 시 예외")
        void createRoot_invalidSlug() {
            assertThatThrownBy(() -> EcommerceCategory.createRoot("전자제품", "Electronics!", 0))
                    .isInstanceOf(CategoryInvariantViolationException.class)
                    .hasMessageContaining("lowercase");
        }

        @Test @DisplayName("slug 가 비어있으면 예외")
        void createRoot_emptySlug() {
            assertThatThrownBy(() -> EcommerceCategory.createRoot("전자제품", "", 0))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("createChild — 하위 카테고리 생성")
    class CreateChild {

        @Test @DisplayName("부모 depth=0 이면 자식 depth=1")
        void createChild_depthIncremented() {
            EcommerceCategory child = EcommerceCategory.createChild("노트북", "laptop", 10L, 0, 0);

            assertThat(child.getDepth()).isEqualTo(1);
            assertThat(child.getParentId()).isEqualTo(10L);
            assertThat(child.isRoot()).isFalse();
        }

        @Test @DisplayName("MAX_DEPTH(2) 초과 시 예외")
        void createChild_exceedsMaxDepth() {
            assertThatThrownBy(() -> EcommerceCategory.createChild("딥", "deep", 1L, 2, 0))
                    .isInstanceOf(InvalidCategoryStateException.class)
                    .hasMessageContaining("depth cannot exceed");
        }

        @Test @DisplayName("parentDepth null 이면 예외")
        void createChild_nullParentDepth() {
            assertThatThrownBy(() -> EcommerceCategory.createChild("x", "x", 1L, null, 0))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class StateTransitions {

        @Test @DisplayName("deactivate → activate 로 돌릴 수 있다")
        void deactivateThenActivate() {
            EcommerceCategory c = EcommerceCategory.createRoot("x", "x", 0);
            c.deactivate();
            assertThat(c.getIsActive()).isFalse();

            c.activate();
            assertThat(c.getIsActive()).isTrue();
        }

        @Test @DisplayName("softDelete 후에는 isDeleted=true, isActive=false")
        void softDelete() {
            EcommerceCategory c = EcommerceCategory.createRoot("x", "x", 0);
            c.softDelete();

            assertThat(c.isDeleted()).isTrue();
            assertThat(c.getIsActive()).isFalse();
            assertThat(c.getDeletedAt()).isNotNull();
        }

        @Test @DisplayName("soft-deleted 상태에서 activate 시도하면 예외")
        void activateOnDeleted() {
            EcommerceCategory c = EcommerceCategory.createRoot("x", "x", 0);
            c.softDelete();

            assertThatThrownBy(c::activate)
                    .isInstanceOf(InvalidCategoryStateException.class)
                    .hasMessageContaining("deleted");
        }
    }

    @Nested
    @DisplayName("changeParent — 부모 변경(이동)")
    class ChangeParent {

        @Test @DisplayName("자기 자신을 부모로 지정하면 예외")
        void selfAsParent() {
            EcommerceCategory c = new EcommerceCategory(5L, "x", "x", null, 0, 0, true, null, null, null);
            assertThatThrownBy(() -> c.changeParent(5L, 0))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @Test @DisplayName("이동 결과 depth 가 MAX_DEPTH 를 초과하면 예외")
        void exceedsMaxDepthOnMove() {
            EcommerceCategory c = EcommerceCategory.createRoot("x", "x", 0);
            assertThatThrownBy(() -> c.changeParent(99L, 2))
                    .isInstanceOf(InvalidCategoryStateException.class)
                    .hasMessageContaining("maximum depth");
        }

        @Test @DisplayName("parentId=null 로 이동 시 root 로 전환 (depth=0)")
        void moveToRoot() {
            EcommerceCategory c = EcommerceCategory.createChild("x", "x", 10L, 0, 0);
            c.changeParent(null, null);

            assertThat(c.getParentId()).isNull();
            assertThat(c.getDepth()).isZero();
            assertThat(c.isRoot()).isTrue();
        }
    }

    @Nested
    @DisplayName("changeSortOrder / updateInfo")
    class MiscBusinessMethods {

        @Test @DisplayName("sortOrder 음수 설정 시 예외")
        void negativeSortOrder() {
            EcommerceCategory c = EcommerceCategory.createRoot("x", "x", 0);
            assertThatThrownBy(() -> c.changeSortOrder(-1))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @Test @DisplayName("updateInfo 로 name/slug 부분 수정")
        void updateInfo_partial() {
            EcommerceCategory c = EcommerceCategory.createRoot("old", "old-slug", 0);
            c.updateInfo("new", null);

            assertThat(c.getName()).isEqualTo("new");
            assertThat(c.getSlug()).isEqualTo("old-slug");
        }

        @Test @DisplayName("updateInfo 의 slug 는 규칙을 따라야 한다")
        void updateInfo_invalidSlug() {
            EcommerceCategory c = EcommerceCategory.createRoot("x", "x", 0);
            assertThatThrownBy(() -> c.updateInfo(null, "BAD_SLUG"))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }
    }
}
