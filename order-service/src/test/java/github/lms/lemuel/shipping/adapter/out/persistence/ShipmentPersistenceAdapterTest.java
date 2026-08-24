package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.Shipment;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 배송 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속).
 * 신규 저장(id==null) 시 엔티티 생성 경로와, 갱신(id!=null) 시 applyState 경로를 모두 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentPersistenceAdapterTest {

    @Mock SpringDataShipmentRepository repository;
    @InjectMocks ShipmentPersistenceAdapter adapter;

    private ShippingAddress addr() {
        return new ShippingAddress("홍길동", "010-1234-5678", "12345",
                "서울시 강남구", "101동 202호", "부재시 경비실");
    }

    private ShipmentJpaEntity entity(Long id) {
        return new ShipmentJpaEntity(id, 500L, "홍길동", "010-1234-5678", "12345",
                "서울시 강남구", "101동 202호", "부재시 경비실", "CJ", "TRK-1",
                ShippingStatus.SHIPPED, LocalDateTime.now(), null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }

    @Test
    @DisplayName("loadByOrderId: 존재 시 도메인 매핑")
    void loadByOrderId() {
        when(repository.findByOrderId(500L)).thenReturn(Optional.of(entity(1L)));
        Optional<Shipment> s = adapter.loadByOrderId(500L);
        assertThat(s).isPresent();
        assertThat(s.get().getOrderId()).isEqualTo(500L);
        assertThat(s.get().getAddress().recipientName()).isEqualTo("홍길동");
        assertThat(s.get().getStatus()).isEqualTo(ShippingStatus.SHIPPED);
    }

    @Test
    @DisplayName("loadById: 존재 시 도메인 매핑")
    void loadById() {
        when(repository.findById(1L)).thenReturn(Optional.of(entity(1L)));
        assertThat(adapter.loadById(1L)).isPresent();
    }

    @Test
    @DisplayName("loadByTrackingNumber: 존재 시 도메인 매핑")
    void loadByTrackingNumber() {
        when(repository.findByCarrierAndTrackingNumber("CJ", "TRK-1"))
                .thenReturn(Optional.of(entity(1L)));
        assertThat(adapter.loadByTrackingNumber("CJ", "TRK-1")).isPresent();
    }

    @Test
    @DisplayName("save: 신규(id==null) 는 새 엔티티를 만들어 저장한다")
    void save_new() {
        Shipment shipment = Shipment.createPending(500L, addr());
        when(repository.save(any(ShipmentJpaEntity.class))).thenReturn(entity(1L));

        Shipment saved = adapter.save(shipment);

        ArgumentCaptor<ShipmentJpaEntity> captor = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getOrderId()).isEqualTo(500L);
        assertThat(captor.getValue().getStatus()).isEqualTo(ShippingStatus.PENDING);
        assertThat(saved.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("save: 기존(id!=null) 은 로딩 후 applyState 로 갱신한다")
    void save_existing() {
        Shipment shipment = Shipment.rehydrate(1L, 500L, addr(), "CJ", "TRK-9",
                ShippingStatus.IN_TRANSIT, LocalDateTime.now(), null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
        ShipmentJpaEntity existing = entity(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ShipmentJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Shipment saved = adapter.save(shipment);

        assertThat(existing.getTrackingNumber()).isEqualTo("TRK-9");
        assertThat(existing.getStatus()).isEqualTo(ShippingStatus.IN_TRANSIT);
        assertThat(saved.getTrackingNumber()).isEqualTo("TRK-9");
    }

    @Test
    @DisplayName("save: 기존 id 인데 DB 에 없으면 예외")
    void save_existingMissing() {
        Shipment shipment = Shipment.rehydrate(99L, 500L, addr(), "CJ", "TRK-9",
                ShippingStatus.SHIPPED, LocalDateTime.now(), null,
                LocalDateTime.now(), LocalDateTime.now());
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(shipment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Shipment not found");
    }
}
