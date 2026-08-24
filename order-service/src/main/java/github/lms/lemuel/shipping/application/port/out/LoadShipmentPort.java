package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.Shipment;

import java.util.Optional;

public interface LoadShipmentPort {
    Optional<Shipment> loadByOrderId(Long orderId);
    Optional<Shipment> loadById(Long shipmentId);
    Optional<Shipment> loadByTrackingNumber(String carrier, String trackingNumber);
}
