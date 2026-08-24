package github.lms.lemuel.category.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CategoryExceptionTest {

    @Test @DisplayName("CategoryDepthExceededException: 깊이 정보가 메시지에 포함된다")
    void categoryDepthExceeded_intConstructor() {
        var ex = new CategoryDepthExceededException(5, 3);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Category depth 5 exceeds maximum allowed depth of 3");
    }

    @Test @DisplayName("CategoryDepthExceededException: 문자열 생성자")
    void categoryDepthExceeded_stringConstructor() {
        var ex = new CategoryDepthExceededException("최대 깊이 초과");
        assertThat(ex.getMessage()).isEqualTo("최대 깊이 초과");
    }

    @Test @DisplayName("CategoryHasChildrenException: 카테고리 ID와 자식 수")
    void categoryHasChildren() {
        var ex = new CategoryHasChildrenException(10L, 3);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Cannot delete category 10: has 3 child categories");
    }

    @Test @DisplayName("CategoryHasProductsException: 카테고리 ID")
    void categoryHasProducts() {
        var ex = new CategoryHasProductsException(20L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Cannot delete category 20: has associated products");
    }

    @Test @DisplayName("CategoryNotFoundException: Long 생성자")
    void categoryNotFound_longConstructor() {
        var ex = new CategoryNotFoundException(5L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Category not found: 5");
    }

    @Test @DisplayName("CategoryNotFoundException: 문자열(slug) 생성자")
    void categoryNotFound_slugConstructor() {
        var ex = new CategoryNotFoundException("electronics");
        assertThat(ex.getMessage()).isEqualTo("Category not found with slug: electronics");
    }

    @Test @DisplayName("CircularReferenceException: ID 쌍 생성자")
    void circularReference_idsConstructor() {
        var ex = new CircularReferenceException(3L, 7L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo(
                "Circular reference detected: category 3 cannot have parent 7 (would create a cycle)");
    }

    @Test @DisplayName("CircularReferenceException: 문자열 생성자")
    void circularReference_stringConstructor() {
        var ex = new CircularReferenceException("순환 참조 감지");
        assertThat(ex.getMessage()).isEqualTo("순환 참조 감지");
    }

    @Test @DisplayName("DuplicateSlugException: slug가 메시지에 포함된다")
    void duplicateSlug() {
        var ex = new DuplicateSlugException("my-category");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Category slug already exists: my-category");
    }
}
