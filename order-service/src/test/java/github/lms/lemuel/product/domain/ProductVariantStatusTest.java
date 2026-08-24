package github.lms.lemuel.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProductVariantStatusTest {

    @Test @DisplayName("enum에 3개의 값이 존재한다")
    void values_count() {
        assertThat(ProductVariantStatus.values()).hasSize(3);
    }

    @Test @DisplayName("모든 enum 값이 올바르게 정의되어 있다")
    void values_defined() {
        assertThat(ProductVariantStatus.values()).containsExactly(
                ProductVariantStatus.ACTIVE,
                ProductVariantStatus.OUT_OF_STOCK,
                ProductVariantStatus.DISCONTINUED);
    }

    @Test @DisplayName("valueOf: 각 값을 올바르게 변환한다")
    void valueOf_all() {
        assertThat(ProductVariantStatus.valueOf("ACTIVE")).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(ProductVariantStatus.valueOf("OUT_OF_STOCK")).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);
        assertThat(ProductVariantStatus.valueOf("DISCONTINUED")).isEqualTo(ProductVariantStatus.DISCONTINUED);
    }

    @Test @DisplayName("valueOf: 잘못된 이름이면 IllegalArgumentException")
    void valueOf_invalid() {
        assertThatThrownBy(() -> ProductVariantStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
