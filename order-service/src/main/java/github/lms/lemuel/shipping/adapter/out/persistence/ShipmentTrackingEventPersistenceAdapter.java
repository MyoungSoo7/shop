package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.LoadShipmentTrackingEventPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentTrackingEventPort;
import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ShipmentTrackingEventPersistenceAdapter
        implements LoadShipmentTrackingEventPort, SaveShipmentTrackingEventPort {

    private final SpringDataShipmentTrackingEventRepository repository;

    public ShipmentTrackingEventPersistenceAdapter(SpringDataShipmentTrackingEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ShipmentTrackingEvent> loadByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByOccurredAtAscIdAsc(orderId).stream()
                .map(ShipmentTrackingEventPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ShipmentTrackingEvent> saveAll(List<ShipmentTrackingEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<ShipmentTrackingEventJpaEntity> entities = events.stream()
                .map(e -> new ShipmentTrackingEventJpaEntity(
                        null, e.orderId(), e.status(), e.source(),
                        e.description(), e.location(), e.occurredAt(), null))
                .toList();
        return repository.saveAll(entities).stream()
                .map(ShipmentTrackingEventPersistenceAdapter::toDomain)
                .toList();
    }

    private static ShipmentTrackingEvent toDomain(ShipmentTrackingEventJpaEntity e) {
        return ShipmentTrackingEvent.rehydrate(e.getId(), e.getOrderId(), e.getStatus(),
                e.getSource(), e.getDescription(), e.getLocation(), e.getOccurredAt());
    }
}
