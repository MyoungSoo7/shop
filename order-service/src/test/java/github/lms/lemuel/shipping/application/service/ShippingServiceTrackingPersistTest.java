package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentTrackingEventPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상태를 바꾼 전이는 <b>반드시</b> 이력을 남긴다 — 저장과 적재가 한 몸이라는 것을 지킨다.
 *
 * <p>여기가 깨지면 화면에는 "배송중"이라고 뜨는데 언제부터인지 아무도 답할 수 없는 상태가 된다.
 */
@ExtendWith(MockitoExtension.class)
class ShippingServiceTrackingPersistTest {

    @Mock LoadShipmentPort loadPort;
    @Mock SaveShipmentPort savePort;
    @Mock github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort restoreStockPort;
    @Mock github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase safetyNumberUseCase;
    @Mock SaveShipmentTrackingEventPort saveTrackingEventPort;
    @InjectMocks ShippingService service;

    private static ShippingAddress addr() {
        return new ShippingAddress("홍길동", "010-1234-5678", "12345", "서울시 강남구", null, null);
    }

    private static Shipment pendingShipment() {
        LocalDateTime now = LocalDateTime.now();
        return Shipment.rehydrate(9L, 500L, addr(), null, null,
                ShippingStatus.PENDING, null, null, now, now);
    }

    @SuppressWarnings("unchecked")
    private List<ShipmentTrackingEvent> capturedEvents() {
        ArgumentCaptor<List<ShipmentTrackingEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveTrackingEventPort).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("createForOrder: 배송 생성과 함께 접수 이력이 적재된다")
    void createRecords() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.empty());
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createForOrder(500L, addr());

        assertThat(capturedEvents()).singleElement().satisfies(e -> {
            assertThat(e.orderId()).isEqualTo(500L);
            assertThat(e.status()).isEqualTo(ShippingStatus.PENDING);
        });
    }

    @Test
    @DisplayName("ship: 출고 이력이 적재된다")
    void shipRecords() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(pendingShipment()));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ship(500L, "CJ대한통운", "TRK-1");

        assertThat(capturedEvents()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(ShippingStatus.SHIPPED);
            assertThat(e.description()).contains("CJ대한통운").doesNotContain("TRK-1");
        });
    }

    @Test
    @DisplayName("전이가 거부되면 저장도 적재도 없다")
    void rejectedTransitionPersistsNothing() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(pendingShipment()));

        assertThatThrownBy(() -> service.markInTransit(500L)).isInstanceOf(RuntimeException.class);

        verify(savePort, never()).save(any());
        verify(saveTrackingEventPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("배송지 변경도 이력을 남긴다 — 상태가 그대로여도 '왜 안 움직이나'에 답할 근거가 된다")
    void changeAddressRecords() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(pendingShipment()));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.changeAddress(500L, new ShippingAddress("김철수", "010-9999-8888", "54321",
                "부산시 해운대구", null, null));

        assertThat(capturedEvents()).singleElement().satisfies(e ->
                assertThat(e.status()).isEqualTo(ShippingStatus.PENDING));
    }
}
