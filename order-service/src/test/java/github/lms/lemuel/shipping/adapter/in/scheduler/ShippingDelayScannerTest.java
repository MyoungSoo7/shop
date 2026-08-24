package github.lms.lemuel.shipping.adapter.in.scheduler;

import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.shipping.adapter.out.persistence.ShipmentJpaEntity;
import github.lms.lemuel.shipping.adapter.out.persistence.SpringDataShipmentRepository;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingDelayScannerTest {

    @Mock
    SpringDataShipmentRepository repository;
    @Mock
    OpsSignalPort opsSignalPort;

    private ShippingDelayScanner scanner() {
        return new ShippingDelayScanner(repository, opsSignalPort, 72, 21_600_000L);
    }

    private ShipmentJpaEntity shipment(long id, long orderId) {
        ShipmentJpaEntity e = mock(ShipmentJpaEntity.class);
        when(e.getId()).thenReturn(id);
        when(e.getOrderId()).thenReturn(orderId);
        return e;
    }

    @Test
    void 임계를_막_넘어선_배송마다_shipping_delayed_신호를_발행한다() {
        // 목 생성/스터빙을 when(...) 밖으로 — thenReturn 인자 안에서 다른 목을 스터빙하면
        // Mockito UnfinishedStubbingException 이 난다.
        ShipmentJpaEntity s1 = shipment(1L, 100L);
        ShipmentJpaEntity s2 = shipment(2L, 200L);
        when(repository.findNewlyDelayed(eq(ShippingStatus.IN_TRANSIT), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(s1, s2));

        scanner().scan();

        ArgumentCaptor<OpsSignal> captor = ArgumentCaptor.forClass(OpsSignal.class);
        verify(opsSignalPort, org.mockito.Mockito.times(2)).emit(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(s ->
                assertThat(s.category()).isEqualTo(OpsSignalCategory.SHIPPING_DELAYED));
        assertThat(captor.getAllValues()).extracting(OpsSignal::entityId).containsExactly("1", "2");
    }

    @Test
    void 지연_건이_없으면_발행하지_않는다() {
        when(repository.findNewlyDelayed(any(), any(), any())).thenReturn(List.of());

        scanner().scan();

        verify(opsSignalPort, never()).emit(any(OpsSignal.class));
    }
}
