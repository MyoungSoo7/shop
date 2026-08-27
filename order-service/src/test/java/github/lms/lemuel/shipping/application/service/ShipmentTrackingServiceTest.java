package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentTrackingEventPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShipmentTimeline;
import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import github.lms.lemuel.shipping.domain.TrackingEventSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentTrackingServiceTest {

    private static final ShippingAddress ADDRESS = new ShippingAddress(
            "홍길동", "010-1234-5678", "12345", "서울시 강남구", null, null);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 20, 9, 0);

    @Mock LoadShipmentPort loadShipmentPort;
    @Mock LoadShipmentTrackingEventPort loadEventPort;
    @Mock CarrierTrackingPort carrierTrackingPort;
    @InjectMocks ShipmentTrackingService service;

    private static Shipment shipment(ShippingStatus status, String trackingNumber,
                                     LocalDateTime shippedAt, LocalDateTime deliveredAt) {
        return Shipment.rehydrate(9L, 500L, ADDRESS, "CJ대한통운", trackingNumber,
                status, shippedAt, deliveredAt, CREATED, CREATED);
    }

    private static ShipmentTrackingEvent internal(LocalDateTime at, String description) {
        return ShipmentTrackingEvent.rehydrate(1L, 500L, ShippingStatus.SHIPPED,
                TrackingEventSource.INTERNAL, description, null, at);
    }

    @Test
    @DisplayName("배송이 없으면 빈 Optional")
    void noShipment() {
        when(loadShipmentPort.loadByOrderId(500L)).thenReturn(Optional.empty());

        assertThat(service.getTimeline(500L)).isEmpty();
        verify(loadEventPort, never()).loadByOrderId(any());
    }

    @Test
    @DisplayName("연동이 꺼져 있으면 내부 이력만 나가고 carrierNote 는 비어 있다 — 없는 연동을 알릴 이유가 없다")
    void carrierDisabledLeavesNoNote() {
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED, "TRK-1", CREATED.plusHours(1), null)));
        when(loadEventPort.loadByOrderId(500L)).thenReturn(List.of(internal(CREATED, "출고")));
        when(carrierTrackingPort.enabled()).thenReturn(false);

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.carrierNote()).isNull();
        assertThat(timeline.events()).hasSize(1);
        verify(carrierTrackingPort, never()).fetch(anyString(), anyString());
    }

    @Test
    @DisplayName("운송장이 없으면 택배사를 부르지 않는다")
    void noTrackingNumberSkipsCarrier() {
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.PENDING, null, null, null)));
        when(loadEventPort.loadByOrderId(500L)).thenReturn(List.of(internal(CREATED, "접수")));

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.carrierNote()).isNull();
        verify(carrierTrackingPort, never()).fetch(anyString(), anyString());
    }

    @Test
    @DisplayName("택배사 스캔이 있으면 내부 이력과 합쳐 나간다")
    void mergesCarrierScans() {
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED, "TRK-1", CREATED.plusHours(1), null)));
        when(loadEventPort.loadByOrderId(500L)).thenReturn(List.of(internal(CREATED, "출고")));
        when(carrierTrackingPort.enabled()).thenReturn(true);
        when(carrierTrackingPort.fetch("CJ대한통운", "TRK-1")).thenReturn(CarrierTrackingPort.Result.of(
                List.of(new CarrierTrackingPort.Scan(ShippingStatus.IN_TRANSIT, "간선상차",
                        "동서울허브", CREATED.plusHours(3)))));

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.carrierNote()).isNull();
        assertThat(timeline.events()).extracting(ShipmentTrackingEvent::description)
                .containsExactly("출고", "간선상차");
        assertThat(timeline.events().get(1).source()).isEqualTo(TrackingEventSource.CARRIER);
    }

    @Test
    @DisplayName("택배사 조회가 실패해도 내부 이력은 그대로 — 빈 목록은 '아무 일도 없었다'로 읽힌다")
    void carrierFailureKeepsInternalEvents() {
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED, "TRK-1", CREATED.plusHours(1), null)));
        when(loadEventPort.loadByOrderId(500L))
                .thenReturn(List.of(internal(CREATED, "접수"), internal(CREATED.plusHours(1), "출고")));
        when(carrierTrackingPort.enabled()).thenReturn(true);
        when(carrierTrackingPort.fetch("CJ대한통운", "TRK-1"))
                .thenReturn(CarrierTrackingPort.Result.unavailable("택배사 배송 정보를 불러오지 못했습니다."));

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.events()).hasSize(2);
        assertThat(timeline.carrierUnavailable()).isTrue();
        assertThat(timeline.carrierNote()).isEqualTo("택배사 배송 정보를 불러오지 못했습니다.");
    }

    @Test
    @DisplayName("어댑터가 규약을 어기고 예외를 던져도 배송 조회는 살아 있다")
    void adapterThrowingDoesNotBreakView() {
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED, "TRK-1", CREATED.plusHours(1), null)));
        when(loadEventPort.loadByOrderId(500L)).thenReturn(List.of(internal(CREATED, "출고")));
        when(carrierTrackingPort.enabled()).thenReturn(true);
        when(carrierTrackingPort.fetch("CJ대한통운", "TRK-1")).thenThrow(new IllegalStateException("boom"));

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.events()).hasSize(1);
        assertThat(timeline.carrierUnavailable()).isTrue();
    }

    @Test
    @DisplayName("이력이 없는 옛 배송은 배송이 실제로 들고 있는 시각으로 최소 타임라인을 합성한다")
    void synthesizesForLegacyShipment() {
        LocalDateTime shippedAt = CREATED.plusHours(2);
        LocalDateTime deliveredAt = CREATED.plusDays(1);
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.DELIVERED, "TRK-1", shippedAt, deliveredAt)));
        when(loadEventPort.loadByOrderId(500L)).thenReturn(List.of());
        when(carrierTrackingPort.enabled()).thenReturn(false);

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.events()).extracting(ShipmentTrackingEvent::occurredAt)
                .containsExactly(CREATED, shippedAt, deliveredAt);
        assertThat(timeline.events()).extracting(ShipmentTrackingEvent::status)
                .containsExactly(ShippingStatus.PENDING, ShippingStatus.SHIPPED, ShippingStatus.DELIVERED);
        assertThat(timeline.events()).allSatisfy(e -> assertThat(e.id()).isNull());
    }

    @Test
    @DisplayName("합성은 모르는 시각을 지어내지 않는다 — 출고 전이면 접수 한 줄뿐")
    void synthesisOmitsUnknownTimes() {
        when(loadShipmentPort.loadByOrderId(500L))
                .thenReturn(Optional.of(shipment(ShippingStatus.PENDING, null, null, null)));
        when(loadEventPort.loadByOrderId(500L)).thenReturn(List.of());

        ShipmentTimeline timeline = service.getTimeline(500L).orElseThrow();

        assertThat(timeline.events()).hasSize(1);
        assertThat(timeline.events().get(0).status()).isEqualTo(ShippingStatus.PENDING);
    }
}
