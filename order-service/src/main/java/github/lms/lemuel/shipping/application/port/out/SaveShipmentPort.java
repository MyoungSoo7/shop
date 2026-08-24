package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.Shipment;

public interface SaveShipmentPort {
    Shipment save(Shipment shipment);
}
