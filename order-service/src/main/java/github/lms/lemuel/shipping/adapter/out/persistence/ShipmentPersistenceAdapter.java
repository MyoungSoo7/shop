package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ShipmentPersistenceAdapter implements LoadShipmentPort, SaveShipmentPort {

    private final SpringDataShipmentRepository repository;

    public ShipmentPersistenceAdapter(SpringDataShipmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Shipment> loadByOrderId(Long orderId) {
        return repository.findByOrderId(orderId).map(ShipmentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Shipment> loadById(Long shipmentId) {
        return repository.findById(shipmentId).map(ShipmentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Shipment> loadByTrackingNumber(String carrier, String trackingNumber) {
        return repository.findByCarrierAndTrackingNumber(carrier, trackingNumber)
                .map(ShipmentPersistenceAdapter::toDomain);
    }

    @Override
    public Shipment save(Shipment shipment) {
        ShipmentJpaEntity entity;
        if (shipment.getId() == null) {
            entity = new ShipmentJpaEntity(
                    null, shipment.getOrderId(),
                    shipment.getAddress().recipientName(), shipment.getAddress().phone(),
                    shipment.getAddress().postalCode(), shipment.getAddress().address1(),
                    shipment.getAddress().address2(), shipment.getAddress().deliveryMemo(),
                    shipment.getCarrier(), shipment.getTrackingNumber(),
                    shipment.getStatus(), shipment.getShippedAt(), shipment.getDeliveredAt(),
                    shipment.getCreatedAt(), shipment.getUpdatedAt()
            );
        } else {
            entity = repository.findById(shipment.getId())
                    .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipment.getId()));
            ShippingAddress addr = shipment.getAddress();
            entity.applyState(addr.recipientName(), addr.phone(), addr.postalCode(),
                    addr.address1(), addr.address2(), addr.deliveryMemo(),
                    shipment.getCarrier(), shipment.getTrackingNumber(),
                    shipment.getStatus(), shipment.getShippedAt(), shipment.getDeliveredAt(),
                    shipment.getUpdatedAt());
        }
        return toDomain(repository.save(entity));
    }

    private static Shipment toDomain(ShipmentJpaEntity e) {
        ShippingAddress addr = new ShippingAddress(
                e.getRecipientName(), e.getPhone(), e.getPostalCode(),
                e.getAddress1(), e.getAddress2(), e.getDeliveryMemo()
        );
        return Shipment.rehydrate(
                e.getId(), e.getOrderId(), addr, e.getCarrier(), e.getTrackingNumber(),
                e.getStatus(), e.getShippedAt(), e.getDeliveredAt(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
