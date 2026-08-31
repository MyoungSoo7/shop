package github.lms.lemuel.seller.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * {@code seller_shipment_requests} 매핑.
 *
 * <p>신청서와 달리 이 원본에는 상태 전이가 없다 — 한 번 쌓이면 끝이고, 그 뒤의 배송 상태는
 * order-service 의 원장이 갖는다. 그래서 엔티티는 {@code ddl-auto: validate} 용 껍데기로 두고
 * 적재는 {@code ON CONFLICT DO NOTHING} 한 줄로 한다.
 */
@Entity
@Table(name = "seller_shipment_requests")
class ShipmentRequestJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 50)
    private String carrier;

    @Column(name = "tracking_number", nullable = false, length = 100)
    private String trackingNumber;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    protected ShipmentRequestJpaEntity() {
    }
}
