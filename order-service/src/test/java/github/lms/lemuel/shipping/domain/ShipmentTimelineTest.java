package github.lms.lemuel.shipping.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentTimelineTest {

    private static final ShippingAddress ADDRESS = new ShippingAddress(
            "홍길동", "010-1234-5678", "12345", "서울시 강남구", null, null);

    private static Shipment shipped() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 10, 0);
        return Shipment.rehydrate(9L, 1L, ADDRESS, "CJ대한통운", "TRK-1",
                ShippingStatus.SHIPPED, now, null, now, now);
    }

    private static ShipmentTrackingEvent internalAt(LocalDateTime at, String description) {
        return ShipmentTrackingEvent.rehydrate(1L, 1L, ShippingStatus.SHIPPED,
                TrackingEventSource.INTERNAL, description, null, at);
    }

    private static ShipmentTrackingEvent carrierAt(LocalDateTime at, String description) {
        return ShipmentTrackingEvent.carrier(1L, ShippingStatus.IN_TRANSIT, description, "동서울허브", at);
    }

    @Test
    @DisplayName("내부 이력과 택배사 스캔을 발생 시각 순으로 합친다")
    void mergesByOccurredAt() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 20, 9, 0);

        ShipmentTimeline timeline = ShipmentTimeline.of(shipped(),
                List.of(internalAt(base, "출고"), internalAt(base.plusHours(5), "완료")),
                List.of(carrierAt(base.plusHours(2), "집화"), carrierAt(base.plusHours(3), "간선상차")),
                null);

        assertThat(timeline.events()).extracting(ShipmentTrackingEvent::description)
                .containsExactly("출고", "집화", "간선상차", "완료");
    }

    @Test
    @DisplayName("같은 시각이면 우리 기록이 먼저 온다 — 사실의 원인이 결과보다 앞선다")
    void internalFirstOnTie() {
        LocalDateTime same = LocalDateTime.of(2026, 8, 20, 9, 0);

        ShipmentTimeline timeline = ShipmentTimeline.of(shipped(),
                List.of(internalAt(same, "출고")), List.of(carrierAt(same, "집화")), null);

        assertThat(timeline.events()).extracting(ShipmentTrackingEvent::source)
                .containsExactly(TrackingEventSource.INTERNAL, TrackingEventSource.CARRIER);
    }

    @Test
    @DisplayName("carrierNote 가 있으면 조회 실패로 본다 — 없으면 실패가 아니다")
    void carrierUnavailableFlag() {
        assertThat(ShipmentTimeline.of(shipped(), List.of(), List.of(), null).carrierUnavailable()).isFalse();
        assertThat(ShipmentTimeline.of(shipped(), List.of(), List.of(), "못 불러왔습니다").carrierUnavailable())
                .isTrue();
    }

    @Test
    @DisplayName("배송의 현재 상태·택배사·운송장을 그대로 옮긴다")
    void copiesShipmentHeader() {
        ShipmentTimeline timeline = ShipmentTimeline.of(shipped(), List.of(), List.of(), null);

        assertThat(timeline.orderId()).isEqualTo(1L);
        assertThat(timeline.status()).isEqualTo(ShippingStatus.SHIPPED);
        assertThat(timeline.carrier()).isEqualTo("CJ대한통운");
        assertThat(timeline.trackingNumber()).isEqualTo("TRK-1");
    }

    @Test
    @DisplayName("events 는 방어 복사된다 — 밖에서 목록을 고칠 수 없다")
    void eventsAreImmutable() {
        ShipmentTimeline timeline = ShipmentTimeline.of(shipped(),
                List.of(internalAt(LocalDateTime.now(), "출고")), List.of(), null);

        assertThatThrownBy(() -> timeline.events().add(internalAt(LocalDateTime.now(), "위조")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
