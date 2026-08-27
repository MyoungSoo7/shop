package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import github.lms.lemuel.shipping.domain.TrackingEventSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentTrackingEventPersistenceAdapterTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 20, 9, 0);

    @Mock SpringDataShipmentTrackingEventRepository repository;
    @InjectMocks ShipmentTrackingEventPersistenceAdapter adapter;

    private static ShipmentTrackingEventJpaEntity entity(Long id, LocalDateTime at, String description) {
        return new ShipmentTrackingEventJpaEntity(id, 500L, ShippingStatus.SHIPPED,
                TrackingEventSource.INTERNAL, description, null, at, at);
    }

    @Test
    @DisplayName("loadByOrderId: 시간순으로 읽어 도메인으로 옮긴다")
    void loadByOrderId() {
        when(repository.findByOrderIdOrderByOccurredAtAscIdAsc(500L))
                .thenReturn(List.of(entity(1L, AT, "접수"), entity(2L, AT.plusHours(1), "출고")));

        List<ShipmentTrackingEvent> events = adapter.loadByOrderId(500L);

        assertThat(events).extracting(ShipmentTrackingEvent::description).containsExactly("접수", "출고");
        assertThat(events.get(0).id()).isEqualTo(1L);
        assertThat(events.get(0).source()).isEqualTo(TrackingEventSource.INTERNAL);
    }

    @Test
    @DisplayName("saveAll: 발생 시각을 그대로 싣는다 — 적재 시각으로 바꿔 달지 않는다")
    void saveAllPreservesOccurredAt() {
        ShipmentTrackingEvent event = ShipmentTrackingEvent.rehydrate(null, 500L, ShippingStatus.SHIPPED,
                TrackingEventSource.INTERNAL, "출고", null, AT);
        when(repository.saveAll(anyList())).thenAnswer(inv -> {
            List<?> given = inv.getArgument(0);
            return List.of(entity(7L, AT, "출고"));
        });

        List<ShipmentTrackingEvent> saved = adapter.saveAll(List.of(event));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShipmentTrackingEventJpaEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(e -> {
            assertThat(e.getId()).isNull();          // 새 줄이다
            assertThat(e.getOccurredAt()).isEqualTo(AT);
            assertThat(e.getCreatedAt()).isNull();   // @PrePersist 가 채운다
        });
        assertThat(saved).singleElement()
                .satisfies(e -> assertThat(e.id()).isEqualTo(7L));
    }

    @Test
    @DisplayName("saveAll: 빈 목록이면 저장소를 부르지 않는다")
    void saveAllEmptyShortCircuits() {
        assertThat(adapter.saveAll(List.of())).isEmpty();
        verify(repository, never()).saveAll(any());
    }
}
