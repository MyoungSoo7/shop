package github.lms.lemuel.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ProductStatusTest {

    @Test @DisplayName("enum에 4개의 값이 존재한다")
    void values_count() {
        assertThat(ProductStatus.values()).hasSize(4);
    }

    @Test @DisplayName("모든 enum 값이 올바르게 정의되어 있다")
    void values_defined() {
        assertThat(ProductStatus.values()).containsExactly(
                ProductStatus.ACTIVE, ProductStatus.INACTIVE,
                ProductStatus.OUT_OF_STOCK, ProductStatus.DISCONTINUED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE", "OUT_OF_STOCK", "DISCONTINUED"})
    @DisplayName("fromString: 대문자 문자열을 올바르게 변환한다")
    void fromString_uppercase(String value) {
        assertThat(ProductStatus.fromString(value)).isEqualTo(ProductStatus.valueOf(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "inactive", "out_of_stock", "discontinued"})
    @DisplayName("fromString: 소문자 문자열을 올바르게 변환한다")
    void fromString_lowercase(String value) {
        assertThat(ProductStatus.fromString(value)).isEqualTo(ProductStatus.valueOf(value.toUpperCase()));
    }

    @Test @DisplayName("fromString: 유효하지 않은 문자열이면 ACTIVE를 반환한다")
    void fromString_invalid_returnsActive() {
        assertThat(ProductStatus.fromString("INVALID")).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test @DisplayName("fromString: 빈 문자열이면 ACTIVE를 반환한다")
    void fromString_empty_returnsActive() {
        assertThat(ProductStatus.fromString("")).isEqualTo(ProductStatus.ACTIVE);
    }
}
