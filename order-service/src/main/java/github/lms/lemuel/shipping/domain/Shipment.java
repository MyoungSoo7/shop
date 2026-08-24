package github.lms.lemuel.shipping.domain;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 배송 도메인 (집합 루트).
 *
 * <p>주문 1 건 = 배송 1 건 (1:1). 멀티 셀러 / 분할 배송은 향후 OrderItem 단위 N 건으로 확장.
 *
 * <p>상태 전이:
 * <ul>
 *   <li>{@link #ship(String, String)} : PENDING/READY → SHIPPED (운송장 발급)</li>
 *   <li>{@link #markInTransit()} : SHIPPED → IN_TRANSIT (택배사 첫 스캔)</li>
 *   <li>{@link #markDelivered()} : SHIPPED/IN_TRANSIT → DELIVERED</li>
 *   <li>{@link #returnShipment()} : DELIVERED → RETURNED</li>
 * </ul>
 */
public class Shipment {

    private Long id;
    private final Long orderId;
    private ShippingAddress address;
    private String carrier;
    private String trackingNumber;
    private ShippingStatus status;
    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static Shipment createPending(Long orderId, ShippingAddress address) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(address, "address");
        LocalDateTime now = LocalDateTime.now();
        return new Shipment(null, orderId, address, null, null,
                ShippingStatus.PENDING, null, null, now, now);
    }

    public static Shipment rehydrate(Long id, Long orderId, ShippingAddress address,
                                      String carrier, String trackingNumber, ShippingStatus status,
                                      LocalDateTime shippedAt, LocalDateTime deliveredAt,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Shipment(id, orderId, address, carrier, trackingNumber,
                status, shippedAt, deliveredAt, createdAt, updatedAt);
    }

    private Shipment(Long id, Long orderId, ShippingAddress address, String carrier,
                     String trackingNumber, ShippingStatus status,
                     LocalDateTime shippedAt, LocalDateTime deliveredAt,
                     LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.address = address;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void markReady() {
        if (status != ShippingStatus.PENDING) {
            throw new InvalidShipmentStateException(status, ShippingStatus.READY);
        }
        status = ShippingStatus.READY;
        touch();
    }

    /**
     * 출고 처리 — 운송장 번호 발급. PENDING 또는 READY 에서 가능.
     */
    public void ship(String carrier, String trackingNumber) {
        if (status != ShippingStatus.PENDING && status != ShippingStatus.READY) {
            throw new InvalidShipmentStateException(status, ShippingStatus.SHIPPED);
        }
        if (carrier == null || carrier.isBlank()) {
            throw new ShipmentInvariantViolationException("carrier 필수");
        }
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new ShipmentInvariantViolationException("trackingNumber 필수");
        }
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = ShippingStatus.SHIPPED;
        this.shippedAt = LocalDateTime.now();
        touch();
    }

    public void markInTransit() {
        if (status != ShippingStatus.SHIPPED) {
            throw new InvalidShipmentStateException(status, ShippingStatus.IN_TRANSIT);
        }
        status = ShippingStatus.IN_TRANSIT;
        touch();
    }

    public void markDelivered() {
        if (status != ShippingStatus.SHIPPED && status != ShippingStatus.IN_TRANSIT) {
            throw new InvalidShipmentStateException(status, ShippingStatus.DELIVERED);
        }
        status = ShippingStatus.DELIVERED;
        deliveredAt = LocalDateTime.now();
        touch();
    }

    public void returnShipment() {
        if (status != ShippingStatus.DELIVERED) {
            throw new InvalidShipmentStateException(status, ShippingStatus.RETURNED);
        }
        status = ShippingStatus.RETURNED;
        touch();
    }

    public void changeAddress(ShippingAddress newAddress) {
        if (status != ShippingStatus.PENDING) {
            throw new InvalidShipmentStateException(status, ShippingStatus.PENDING);
        }
        Objects.requireNonNull(newAddress, "newAddress");
        this.address = newAddress;
        touch();
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1회만 부여");
        this.id = id;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public ShippingAddress getAddress() { return address; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public ShippingStatus getStatus() { return status; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
