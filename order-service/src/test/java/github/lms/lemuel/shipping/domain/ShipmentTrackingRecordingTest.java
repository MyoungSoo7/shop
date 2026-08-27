package github.lms.lemuel.shipping.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전이가 이력을 남기는지 — 그리고 <b>기록이 도메인에 있는 이유</b>를 지키는지.
 *
 * <p>기록을 서비스에 두면 새 진입점(송장 일괄 등록 같은)이 늘 때마다 기록을 잊을 수 있다.
 * 여기 있으면 상태를 바꾼 모든 경로가 반드시 흔적을 남긴다 — 잊을 자리가 없다.
 */
class ShipmentTrackingRecordingTest {

    private static final ShippingAddress ADDRESS = new ShippingAddress(
            "홍길동", "010-1234-5678", "12345", "서울시 강남구", "456호", null);

    private static Shipment pending() {
        return Shipment.createPending(1L, ADDRESS);
    }

    @Test
    @DisplayName("createPending: 생성만으로 이미 한 줄이 남는다")
    void createLeavesEvent() {
        List<ShipmentTrackingEvent> events = pending().drainPendingEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).status()).isEqualTo(ShippingStatus.PENDING);
        assertThat(events.get(0).source()).isEqualTo(TrackingEventSource.INTERNAL);
        assertThat(events.get(0).orderId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("모든 전이가 한 줄씩 남기고, 상태는 전이 후 값으로 찍힌다")
    void everyTransitionRecords() {
        Shipment shipment = pending();
        shipment.drainPendingEvents();

        shipment.markReady();
        shipment.ship("CJ대한통운", "1234-5678");
        shipment.markInTransit();
        shipment.markDelivered();
        shipment.returnShipment();

        List<ShipmentTrackingEvent> events = shipment.drainPendingEvents();

        assertThat(events).extracting(ShipmentTrackingEvent::status).containsExactly(
                ShippingStatus.READY, ShippingStatus.SHIPPED, ShippingStatus.IN_TRANSIT,
                ShippingStatus.DELIVERED, ShippingStatus.RETURNED);
        assertThat(events).allSatisfy(e ->
                assertThat(e.source()).isEqualTo(TrackingEventSource.INTERNAL));
    }

    @Test
    @DisplayName("ship: 운송장 번호는 이력 문구에 싣지 않는다")
    void shipDoesNotLeakTrackingNumber() {
        Shipment shipment = pending();
        shipment.drainPendingEvents();

        shipment.ship("CJ대한통운", "1234-5678-9012");

        ShipmentTrackingEvent event = shipment.drainPendingEvents().get(0);
        assertThat(event.description()).contains("CJ대한통운").doesNotContain("1234-5678-9012");
    }

    @Test
    @DisplayName("changeAddress: 상태가 그대로여도 이력은 남고, 바뀐 주소는 싣지 않는다")
    void changeAddressRecordsWithoutAddress() {
        Shipment shipment = pending();
        shipment.drainPendingEvents();

        shipment.changeAddress(new ShippingAddress("김철수", "010-9999-8888", "54321",
                "부산시 해운대구", null, null));

        List<ShipmentTrackingEvent> events = shipment.drainPendingEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).status()).isEqualTo(ShippingStatus.PENDING);
        assertThat(events.get(0).description())
                .doesNotContain("부산시").doesNotContain("김철수").doesNotContain("010-9999-8888");
    }

    @Test
    @DisplayName("전이가 실패하면 이력도 남지 않는다")
    void failedTransitionLeavesNothing() {
        Shipment shipment = pending();
        shipment.drainPendingEvents();

        try {
            shipment.markInTransit(); // PENDING 에서는 불가
        } catch (RuntimeException expected) {
            // 상태가 안 바뀌었으므로 이력도 없어야 한다
        }

        assertThat(shipment.drainPendingEvents()).isEmpty();
    }

    @Test
    @DisplayName("drainPendingEvents: 꺼내면 사라진다 — 두 번 저장해도 이력이 두 벌 생기지 않는다")
    void drainIsIdempotentAcrossSaves() {
        Shipment shipment = pending();

        assertThat(shipment.drainPendingEvents()).hasSize(1);
        assertThat(shipment.drainPendingEvents()).isEmpty();
    }

    @Test
    @DisplayName("rehydrate: 되살린 배송은 이력을 다시 쓰지 않는다")
    void rehydratedHasNoPendingEvents() {
        LocalDateTime now = LocalDateTime.now();
        Shipment shipment = Shipment.rehydrate(9L, 1L, ADDRESS, "CJ대한통운", "TRK-1",
                ShippingStatus.SHIPPED, now, null, now, now);

        assertThat(shipment.drainPendingEvents()).isEmpty();
    }
}
