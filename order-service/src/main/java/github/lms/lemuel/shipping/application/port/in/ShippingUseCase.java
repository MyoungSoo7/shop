package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;

public interface ShippingUseCase {

    /**
     * 주문에 대해 PENDING 배송 생성. 같은 주문에 이미 배송이 있으면 IllegalStateException.
     */
    Shipment createForOrder(Long orderId, ShippingAddress address);

    Shipment changeAddress(Long orderId, ShippingAddress newAddress);

    /**
     * 출고 처리 — 운송장 번호 발급. PENDING/READY 에서만 가능.
     */
    Shipment ship(Long orderId, String carrier, String trackingNumber);

    Shipment markInTransit(Long orderId);
    Shipment markDelivered(Long orderId);
    Shipment markReturned(Long orderId);
}
