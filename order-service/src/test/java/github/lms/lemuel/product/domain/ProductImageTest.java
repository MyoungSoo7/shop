package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductImageTest {

    private ProductImage validImage() {
        return ProductImage.create(1L, "a.jpg", "stored.jpg", "/path/a.jpg",
                "/url/a.jpg", "image/jpeg", 1024L, 100, 100, 0);
    }

    @Test @DisplayName("create - 정상 생성, isPrimary=false")
    void create_valid() {
        ProductImage i = validImage();
        assertThat(i.getContentType()).isEqualTo("image/jpeg");
        assertThat(i.getIsPrimary()).isFalse();
        assertThat(i.isDeleted()).isFalse();
    }

    @Test @DisplayName("허용되지 않은 content type 은 예외")
    void invalidContentType() {
        assertThatThrownBy(() -> ProductImage.create(1L, "a.gif", "s", "/p", "/u",
                "image/gif", 1024L, 100, 100, 0))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("5MB 초과 시 예외")
    void exceedsMaxFileSize() {
        long tooLarge = 6 * 1024 * 1024;
        assertThatThrownBy(() -> ProductImage.create(1L, "a.jpg", "s", "/p", "/u",
                "image/jpeg", tooLarge, 100, 100, 0))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("파일 크기 0 이하 예외")
    void zeroFileSize() {
        assertThatThrownBy(() -> ProductImage.create(1L, "a.jpg", "s", "/p", "/u",
                "image/jpeg", 0L, 100, 100, 0))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("markAsPrimary / unmarkAsPrimary")
    void togglePrimary() {
        ProductImage i = validImage();
        i.markAsPrimary();
        assertThat(i.getIsPrimary()).isTrue();
        i.unmarkAsPrimary();
        assertThat(i.getIsPrimary()).isFalse();
    }

    @Test @DisplayName("삭제된 이미지는 대표로 지정 불가")
    void markAsPrimary_onDeleted() {
        ProductImage i = validImage();
        i.softDelete();
        assertThatThrownBy(i::markAsPrimary)
                .isInstanceOf(InvalidProductStateException.class);
    }

    @Test @DisplayName("softDelete 시 대표 이미지 해제됨")
    void softDelete_unmarksPrimary() {
        ProductImage i = validImage();
        i.markAsPrimary();
        i.softDelete();

        assertThat(i.isDeleted()).isTrue();
        assertThat(i.getIsPrimary()).isFalse();
    }

    @Test @DisplayName("changeOrder 음수 예외")
    void changeOrder_negative() {
        ProductImage i = validImage();
        assertThatThrownBy(() -> i.changeOrder(-1))
                .isInstanceOf(ProductInvariantViolationException.class);
    }
}
