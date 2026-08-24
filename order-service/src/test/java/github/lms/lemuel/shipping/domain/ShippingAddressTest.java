package github.lms.lemuel.shipping.domain;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ShippingAddressTest {

    @Test @DisplayName("유효한 주소 생성")
    void valid() {
        var addr = new ShippingAddress("홍길동", "010-1234-5678", "06234", "서울시 강남구", "101호", "부재시 문앞");
        assertThat(addr.recipientName()).isEqualTo("홍길동");
        assertThat(addr.phone()).isEqualTo("010-1234-5678");
        assertThat(addr.postalCode()).isEqualTo("06234");
        assertThat(addr.address1()).isEqualTo("서울시 강남구");
        assertThat(addr.address2()).isEqualTo("101호");
        assertThat(addr.deliveryMemo()).isEqualTo("부재시 문앞");
    }

    @Test @DisplayName("address2와 deliveryMemo는 null 허용")
    void nullOptionalFields() {
        var addr = new ShippingAddress("홍길동", "010-1234-5678", "06234", "서울시", null, null);
        assertThat(addr.address2()).isNull();
        assertThat(addr.deliveryMemo()).isNull();
    }

    @Test @DisplayName("null recipientName이면 NPE")
    void nullRecipientName() {
        assertThatThrownBy(() -> new ShippingAddress(null, "010", "06234", "서울", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test @DisplayName("빈 recipientName이면 예외")
    void blankRecipientName() {
        assertThatThrownBy(() -> new ShippingAddress("  ", "010", "06234", "서울", null, null))
                .isInstanceOf(ShipmentInvariantViolationException.class)
                .hasMessage("수령인 이름 필수");
    }

    @Test @DisplayName("null phone이면 NPE")
    void nullPhone() {
        assertThatThrownBy(() -> new ShippingAddress("홍길동", null, "06234", "서울", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test @DisplayName("빈 phone이면 예외")
    void blankPhone() {
        assertThatThrownBy(() -> new ShippingAddress("홍길동", "  ", "06234", "서울", null, null))
                .isInstanceOf(ShipmentInvariantViolationException.class)
                .hasMessage("전화번호 필수");
    }

    @Test @DisplayName("null postalCode이면 NPE")
    void nullPostalCode() {
        assertThatThrownBy(() -> new ShippingAddress("홍길동", "010", null, "서울", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test @DisplayName("빈 address1이면 예외")
    void blankAddress1() {
        assertThatThrownBy(() -> new ShippingAddress("홍길동", "010", "06234", "  ", null, null))
                .isInstanceOf(ShipmentInvariantViolationException.class)
                .hasMessage("주소 필수");
    }

    @Test @DisplayName("record equality")
    void equality() {
        var a = new ShippingAddress("홍길동", "010", "06234", "서울", null, null);
        var b = new ShippingAddress("홍길동", "010", "06234", "서울", null, null);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
