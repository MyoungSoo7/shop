package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 반품 회수 완료 시 재고 원복.
 *
 * <p>배송 후 환불은 물건이 고객 손에 있어 재고를 되돌리지 않는다(Order 도메인 규칙). 그 물건이 실제로
 * 돌아온 것이 확인되는 지점이 바로 여기다 — 이 경로가 없으면 배송 후 환불분의 재고가 영영 돌아오지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ShippingReturnStockRestoreTest {

    @Mock LoadShipmentPort loadPort;
    @Mock SaveShipmentPort savePort;
    @Mock RestoreReturnedOrderStockPort restoreStockPort;
    @InjectMocks ShippingService service;

    private ShippingAddress addr() {
        return new ShippingAddress("받는이", "010-0000-0000", "12345", "서울시", "101호", null);
    }

    private Shipment delivered(Long orderId) {
        Shipment s = Shipment.createPending(orderId, addr());
        s.ship("CJ", "1234567890");
        s.markInTransit();
        s.markDelivered();
        return s;
    }

    @Test @DisplayName("반품 회수가 확정되면 그 주문의 재고를 되돌린다")
    void markReturned_restoresOrderStock() {
        when(loadPort.loadByOrderId(7L)).thenReturn(Optional.of(delivered(7L)));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markReturned(7L);

        verify(restoreStockPort).restoreReturnedOrderStock(7L);
    }

    @Test @DisplayName("배송 완료 전 반품 시도는 차단되고 재고도 건드리지 않는다")
    void markReturned_beforeDelivered_blocked() {
        Shipment inTransit = Shipment.createPending(8L, addr());
        inTransit.ship("CJ", "1234567890");
        inTransit.markInTransit();
        when(loadPort.loadByOrderId(8L)).thenReturn(Optional.of(inTransit));

        assertThatThrownBy(() -> service.markReturned(8L))
                .isInstanceOf(InvalidShipmentStateException.class);

        verifyNoInteractions(restoreStockPort);
    }

    @Test @DisplayName("배송 완료 처리는 재고를 건드리지 않는다")
    void markDelivered_doesNotRestoreStock() {
        Shipment s = Shipment.createPending(9L, addr());
        s.ship("CJ", "1234567890");
        s.markInTransit();
        when(loadPort.loadByOrderId(9L)).thenReturn(Optional.of(s));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markDelivered(9L);

        verify(restoreStockPort, never()).restoreReturnedOrderStock(anyLong());
    }
}
