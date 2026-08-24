package github.lms.lemuel.shipping.domain;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentTest {

    private static final ShippingAddress ADDRESS = new ShippingAddress(
            "홍길동", "010-1234-5678", "12345",
            "서울시 강남구 테헤란로 123", "456호", "부재시 경비실"
    );

    @Test
    @DisplayName("createPending: 배송지 + PENDING 상태로 생성")
    void create() {
        Shipment s = Shipment.createPending(1L, ADDRESS);

        assertThat(s.getStatus()).isEqualTo(ShippingStatus.PENDING);
        assertThat(s.getOrderId()).isEqualTo(1L);
        assertThat(s.getAddress().recipientName()).isEqualTo("홍길동");
        assertThat(s.getCarrier()).isNull();
        assertThat(s.getTrackingNumber()).isNull();
    }

    @Test
    @DisplayName("ship: PENDING → SHIPPED, 운송장 번호 발급, shippedAt 기록")
    void ship_fromPending() {
        Shipment s = Shipment.createPending(1L, ADDRESS);

        s.ship("CJ대한통운", "1234567890");

        assertThat(s.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
        assertThat(s.getCarrier()).isEqualTo("CJ대한통운");
        assertThat(s.getTrackingNumber()).isEqualTo("1234567890");
        assertThat(s.getShippedAt()).isNotNull();
    }

    @Test
    @DisplayName("ship: READY → SHIPPED 가능")
    void ship_fromReady() {
        Shipment s = Shipment.createPending(1L, ADDRESS);
        s.markReady();

        s.ship("한진택배", "TRACK-001");

        assertThat(s.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
    }

    @Test
    @DisplayName("ship: 이미 출고된 상태에서 재호출 시 IllegalStateException")
    void ship_alreadyShipped() {
        Shipment s = Shipment.createPending(1L, ADDRESS);
        s.ship("CJ", "T1");

        assertThatThrownBy(() -> s.ship("CJ", "T2"))
                .isInstanceOfSatisfying(InvalidShipmentStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(ShippingStatus.SHIPPED);
                    assertThat(ex.getTo()).isEqualTo(ShippingStatus.SHIPPED);
                });
    }

    @Test
    @DisplayName("배송 상태머신 전체 흐름: PENDING → READY → SHIPPED → IN_TRANSIT → DELIVERED")
    void fullLifecycle() {
        Shipment s = Shipment.createPending(1L, ADDRESS);
        s.markReady();
        s.ship("CJ", "T1");
        s.markInTransit();
        s.markDelivered();

        assertThat(s.getStatus()).isEqualTo(ShippingStatus.DELIVERED);
        assertThat(s.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("returnShipment: DELIVERED → RETURNED")
    void returnAfterDelivery() {
        Shipment s = Shipment.createPending(1L, ADDRESS);
        s.ship("CJ", "T1");
        s.markDelivered();

        s.returnShipment();

        assertThat(s.getStatus()).isEqualTo(ShippingStatus.RETURNED);
    }

    @Test
    @DisplayName("returnShipment: DELIVERED 가 아닌 상태에서 IllegalStateException")
    void returnFromInvalidState() {
        Shipment s = Shipment.createPending(1L, ADDRESS);

        assertThatThrownBy(s::returnShipment).isInstanceOf(InvalidShipmentStateException.class);
    }

    @Test
    @DisplayName("changeAddress: PENDING 에서만 가능, 출고 후엔 IllegalStateException")
    void changeAddress_onlyPending() {
        Shipment s = Shipment.createPending(1L, ADDRESS);

        ShippingAddress newAddr = new ShippingAddress(
                "김영희", "010-9999-8888", "54321",
                "부산시 해운대구 우동 1", null, null);

        s.changeAddress(newAddr);
        assertThat(s.getAddress().recipientName()).isEqualTo("김영희");

        s.ship("CJ", "T1");
        assertThatThrownBy(() -> s.changeAddress(ADDRESS))
                .isInstanceOf(InvalidShipmentStateException.class);
    }

    @Test
    @DisplayName("ship: carrier / trackingNumber 비어있으면 IllegalArgumentException")
    void ship_validation() {
        Shipment s = Shipment.createPending(1L, ADDRESS);

        assertThatThrownBy(() -> s.ship("", "T1")).isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> s.ship("CJ", "")).isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> s.ship(null, "T1")).isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("ShippingAddress: 필수 필드 비어있으면 즉시 거부")
    void address_validation() {
        assertThatThrownBy(() -> new ShippingAddress("", "010", "12345", "addr", null, null))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> new ShippingAddress("홍길동", "", "12345", "addr", null, null))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }
}
