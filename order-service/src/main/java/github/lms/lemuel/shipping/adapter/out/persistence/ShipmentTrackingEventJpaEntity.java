package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.ShippingStatus;
import github.lms.lemuel.shipping.domain.TrackingEventSource;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 배송 추적 이력 — <b>추가만 하고 고치지 않는</b> 테이블.
 *
 * <p>수정자(setter)나 {@code applyState} 를 두지 않은 것은 의도다. 이미 일어난 일의 시각이나
 * 문구를 나중에 고칠 수 있으면 타임라인은 사실 기록이 아니라 편집 가능한 서술이 된다.
 */
@Entity
@Table(name = "shipment_tracking_events")
public class ShipmentTrackingEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShippingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrackingEventSource source;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 200)
    private String location;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ShipmentTrackingEventJpaEntity() { }

    public ShipmentTrackingEventJpaEntity(Long id, Long orderId, ShippingStatus status,
                                          TrackingEventSource source, String description,
                                          String location, LocalDateTime occurredAt,
                                          LocalDateTime createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.status = status;
        this.source = source;
        this.description = description;
        this.location = location;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public ShippingStatus getStatus() { return status; }
    public TrackingEventSource getSource() { return source; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
