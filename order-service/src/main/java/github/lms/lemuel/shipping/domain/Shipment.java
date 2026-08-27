package github.lms.lemuel.shipping.domain;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p><b>전이는 흔적을 남긴다.</b> 상태 칸을 덮어쓰기만 하면 "지금 어디까지 왔는지"는 알아도
 * "언제 그렇게 됐는지"는 사라진다. 그래서 모든 전이가 {@link ShipmentTrackingEvent} 를 한 줄씩
 * 쌓고({@link #pendingEvents}), 저장하는 쪽이 그것을 꺼내 적재한다
 * ({@link #drainPendingEvents()}). 기록을 서비스 계층에 두지 않은 이유는 전이 경로가 하나가
 * 아니기 때문이다 — 송장 일괄 등록처럼 다른 진입점이 늘어도 여기를 지나가는 한 이력은 남는다.
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

    /**
     * 아직 적재되지 않은 추적 이벤트. 되살린 배송에서는 비어 있다 — 이미 저장된 과거 이력을
     * 다시 쓰면 조회할 때마다 같은 줄이 늘어난다.
     */
    private final List<ShipmentTrackingEvent> pendingEvents = new ArrayList<>();

    public static Shipment createPending(Long orderId, ShippingAddress address) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(address, "address");
        LocalDateTime now = LocalDateTime.now();
        Shipment shipment = new Shipment(null, orderId, address, null, null,
                ShippingStatus.PENDING, null, null, now, now);
        shipment.record("주문이 접수되어 배송 준비를 시작합니다.");
        return shipment;
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
        record("상품을 준비해 출고를 기다리고 있습니다.");
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
        // 운송장 번호는 이력 문구에 싣지 않는다 — 배송 조회 응답이 이미 정본으로 내려주고,
        // 이력은 문의·감사 로그로도 흘러 다니므로 식별 정보를 늘릴 이유가 없다.
        record(carrier + "에 상품을 인계했습니다.");
        touch();
    }

    public void markInTransit() {
        if (status != ShippingStatus.SHIPPED) {
            throw new InvalidShipmentStateException(status, ShippingStatus.IN_TRANSIT);
        }
        status = ShippingStatus.IN_TRANSIT;
        record("상품이 배송지로 이동하고 있습니다.");
        touch();
    }

    public void markDelivered() {
        if (status != ShippingStatus.SHIPPED && status != ShippingStatus.IN_TRANSIT) {
            throw new InvalidShipmentStateException(status, ShippingStatus.DELIVERED);
        }
        status = ShippingStatus.DELIVERED;
        deliveredAt = LocalDateTime.now();
        record("상품이 배송지에 도착했습니다.");
        touch();
    }

    public void returnShipment() {
        if (status != ShippingStatus.DELIVERED) {
            throw new InvalidShipmentStateException(status, ShippingStatus.RETURNED);
        }
        status = ShippingStatus.RETURNED;
        record("반품이 처리되었습니다.");
        touch();
    }

    public void changeAddress(ShippingAddress newAddress) {
        if (status != ShippingStatus.PENDING) {
            throw new InvalidShipmentStateException(status, ShippingStatus.PENDING);
        }
        Objects.requireNonNull(newAddress, "newAddress");
        this.address = newAddress;
        // 상태는 그대로지만 이력에는 남긴다 — 남기지 않으면 "왜 아직 안 움직이나"에 답할 근거가
        // 없다. 바뀐 주소 자체는 싣지 않는다(이력이 배송지 변경 전후를 모두 보관하게 된다).
        record("배송지가 변경되었습니다.");
        touch();
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1회만 부여");
        this.id = id;
    }

    private void record(String description) {
        pendingEvents.add(ShipmentTrackingEvent.internal(orderId, status, description));
    }

    /**
     * 쌓인 이벤트를 꺼내 비운다. 저장에 성공한 쪽이 한 번만 가져가도록 <b>꺼내면 사라진다</b> —
     * 그렇지 않으면 같은 배송을 두 번 저장할 때 이력이 두 벌 생긴다.
     */
    public List<ShipmentTrackingEvent> drainPendingEvents() {
        List<ShipmentTrackingEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
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
