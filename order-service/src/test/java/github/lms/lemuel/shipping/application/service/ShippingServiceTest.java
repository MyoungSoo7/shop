package github.lms.lemuel.shipping.application.service;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShippingServiceTest {

    @Mock LoadShipmentPort loadPort;
    @Mock SaveShipmentPort savePort;
    @Mock github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase safetyNumberUseCase;
    @InjectMocks ShippingService service;

    private ShippingAddress addr() {
        return new ShippingAddress("홍길동", "010-1234-5678", "06234", "서울시", null, null);
    }

    @Test @DisplayName("createForOrder: 성공")
    void createForOrder() {
        when(loadPort.loadByOrderId(1L)).thenReturn(Optional.empty());
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Shipment result = service.createForOrder(1L, addr());
        assertThat(result).isNotNull();
        verify(savePort).save(any());
    }

    @Test @DisplayName("createForOrder: 이미 존재하면 예외")
    void createForOrder_alreadyExists() {
        when(loadPort.loadByOrderId(1L)).thenReturn(Optional.of(mock(Shipment.class)));
        assertThatThrownBy(() -> service.createForOrder(1L, addr()))
                .isInstanceOf(ShipmentInvariantViolationException.class)
                .hasMessageContaining("이미 배송이 생성된 주문");
    }

    @Test @DisplayName("ship: 출고 처리")
    void ship() {
        Shipment s = Shipment.createPending(1L, addr());
        s.changeAddress(addr()); // ensure READY
        when(loadPort.loadByOrderId(1L)).thenReturn(Optional.of(s));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Shipment result = service.ship(1L, "CJ대한통운", "1234567890");
        assertThat(result).isNotNull();
    }

    @Test @DisplayName("mustExist: 배송 없으면 예외")
    void mustExist_notFound() {
        when(loadPort.loadByOrderId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeAddress(99L, addr()))
                .isInstanceOf(ShipmentInvariantViolationException.class)
                .hasMessageContaining("배송 없음");
    }
}
