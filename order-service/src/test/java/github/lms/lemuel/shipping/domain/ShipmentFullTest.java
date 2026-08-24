package github.lms.lemuel.shipping.domain;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Shipment — 상태 전이/주소변경/재수화 커버리지")
class ShipmentFullTest {

    private ShippingAddress addr() {
        return new ShippingAddress("홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", "부재 시 경비실");
    }

    private Shipment pending() {
        return Shipment.createPending(1L, addr());
    }

    @Test
    @DisplayName("createPending → markReady → ship → markInTransit → markDelivered → returnShipment")
    void fullHappyPath() {
        Shipment s = pending();
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.PENDING);
        s.markReady();
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.READY);
        s.ship("CJ대한통운", "TRK-1");
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
        assertThat(s.getCarrier()).isEqualTo("CJ대한통운");
        assertThat(s.getTrackingNumber()).isEqualTo("TRK-1");
        assertThat(s.getShippedAt()).isNotNull();
        s.markInTransit();
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.IN_TRANSIT);
        s.markDelivered();
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.DELIVERED);
        assertThat(s.getDeliveredAt()).isNotNull();
        s.returnShipment();
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.RETURNED);
    }

    @Test
    @DisplayName("ship — carrier/trackingNumber 필수, 잘못된 상태 거부")
    void ship_guards() {
        Shipment s = pending();
        assertThatThrownBy(() -> s.ship("", "T")).isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> s.ship("C", "")).isInstanceOf(ShipmentInvariantViolationException.class);
        s.ship("C", "T");
        assertThatThrownBy(() -> s.ship("C", "T2")).isInstanceOf(InvalidShipmentStateException.class);
    }

    @Test
    @DisplayName("markReady/markInTransit/markDelivered/returnShipment — 잘못된 상태 거부")
    void transition_guards() {
        Shipment s = pending();
        assertThatThrownBy(s::markInTransit).isInstanceOf(InvalidShipmentStateException.class);
        assertThatThrownBy(s::markDelivered).isInstanceOf(InvalidShipmentStateException.class);
        assertThatThrownBy(s::returnShipment).isInstanceOf(InvalidShipmentStateException.class);
        s.markReady();
        assertThatThrownBy(s::markReady).isInstanceOf(InvalidShipmentStateException.class);
    }

    @Test
    @DisplayName("changeAddress — PENDING 에서만 허용")
    void changeAddress() {
        Shipment s = pending();
        ShippingAddress newAddr = new ShippingAddress("김철수", "010-0000-0000", "12345", "부산", null, null);
        s.changeAddress(newAddr);
        assertThat(s.getAddress().recipientName()).isEqualTo("김철수");
        s.markReady();
        assertThatThrownBy(() -> s.changeAddress(newAddr)).isInstanceOf(InvalidShipmentStateException.class);
    }

    @Test
    @DisplayName("assignId — 1회만, 재부여 예외")
    void assignId() {
        Shipment s = pending();
        assertThat(s.getId()).isNull();
        s.assignId(99L);
        assertThat(s.getId()).isEqualTo(99L);
        assertThatThrownBy(() -> s.assignId(100L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rehydrate — 영속 상태 복원 및 접근자")
    void rehydrate() {
        LocalDateTime now = LocalDateTime.now();
        Shipment s = Shipment.rehydrate(5L, 6L, addr(), "우체국", "TRK-9",
                ShippingStatus.DELIVERED, now, now, now, now);
        assertThat(s.getId()).isEqualTo(5L);
        assertThat(s.getOrderId()).isEqualTo(6L);
        assertThat(s.getCarrier()).isEqualTo("우체국");
        assertThat(s.getTrackingNumber()).isEqualTo("TRK-9");
        assertThat(s.getStatus()).isEqualTo(ShippingStatus.DELIVERED);
        assertThat(s.getCreatedAt()).isEqualTo(now);
        assertThat(s.getUpdatedAt()).isEqualTo(now);
    }
}
