package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 배송 서비스 상태전이 보완 테스트 — 기존 ShippingServiceTest 가 다루지 않는
 * changeAddress 성공/markInTransit/markDelivered/markReturned 경로를 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class ShippingServiceTransitionsTest {

    @Mock LoadShipmentPort loadPort;
    @Mock SaveShipmentPort savePort;
    // 반품 회수 시 order 재고 원복을 요청하는 포트 — 전이 자체를 보는 이 테스트에서는 호출만 흡수한다.
    @Mock github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort restoreStockPort;
    @InjectMocks ShippingService service;

    private ShippingAddress addr() {
        return new ShippingAddress("홍길동", "010-1234-5678", "12345", "서울시", null, null);
    }

    private Shipment shipmentIn(ShippingStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return Shipment.rehydrate(1L, 500L, addr(), "CJ", "TRK-1", status,
                status == ShippingStatus.PENDING ? null : now, null, now, now);
    }

    @Test
    @DisplayName("changeAddress: PENDING 배송지 변경 저장")
    void changeAddress() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipmentIn(ShippingStatus.PENDING)));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Shipment s = service.changeAddress(500L,
                new ShippingAddress("김철수", "010-0000-0000", "54321", "부산시", null, null));

        assertThat(s.getAddress().recipientName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("markInTransit: SHIPPED → IN_TRANSIT")
    void markInTransit() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipmentIn(ShippingStatus.SHIPPED)));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.markInTransit(500L).getStatus()).isEqualTo(ShippingStatus.IN_TRANSIT);
    }

    @Test
    @DisplayName("markDelivered: SHIPPED → DELIVERED")
    void markDelivered() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipmentIn(ShippingStatus.SHIPPED)));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.markDelivered(500L).getStatus()).isEqualTo(ShippingStatus.DELIVERED);
    }

    @Test
    @DisplayName("markReturned: DELIVERED → RETURNED")
    void markReturned() {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipmentIn(ShippingStatus.DELIVERED)));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.markReturned(500L).getStatus()).isEqualTo(ShippingStatus.RETURNED);
    }
}
