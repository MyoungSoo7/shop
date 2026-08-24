package github.lms.lemuel.shipping.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ShippingStatusTest {

    @Test @DisplayName("enum에 6개의 값이 존재한다")
    void values_count() {
        assertThat(ShippingStatus.values()).hasSize(6);
    }

    @Test @DisplayName("모든 상태가 정의되어 있다")
    void values_defined() {
        assertThat(ShippingStatus.values()).containsExactly(
                ShippingStatus.PENDING, ShippingStatus.READY,
                ShippingStatus.SHIPPED, ShippingStatus.IN_TRANSIT,
                ShippingStatus.DELIVERED, ShippingStatus.RETURNED);
    }

    @Test @DisplayName("valueOf: 각 값 변환")
    void valueOf() {
        assertThat(ShippingStatus.valueOf("PENDING")).isEqualTo(ShippingStatus.PENDING);
        assertThat(ShippingStatus.valueOf("DELIVERED")).isEqualTo(ShippingStatus.DELIVERED);
        assertThat(ShippingStatus.valueOf("RETURNED")).isEqualTo(ShippingStatus.RETURNED);
    }
}
