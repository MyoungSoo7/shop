package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.ShippingStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipments")
public class ShipmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(nullable = false, length = 200)
    private String address1;

    @Column(length = 200)
    private String address2;

    @Column(name = "delivery_memo", length = 500)
    private String deliveryMemo;

    @Column(length = 50)
    private String carrier;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShippingStatus status;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ShipmentJpaEntity() { }

    public ShipmentJpaEntity(Long id, Long orderId, String recipientName, String phone,
                              String postalCode, String address1, String address2, String deliveryMemo,
                              String carrier, String trackingNumber, ShippingStatus status,
                              LocalDateTime shippedAt, LocalDateTime deliveredAt,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.recipientName = recipientName;
        this.phone = phone;
        this.postalCode = postalCode;
        this.address1 = address1;
        this.address2 = address2;
        this.deliveryMemo = deliveryMemo;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getPostalCode() { return postalCode; }
    public String getAddress1() { return address1; }
    public String getAddress2() { return address2; }
    public String getDeliveryMemo() { return deliveryMemo; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public ShippingStatus getStatus() { return status; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void applyState(String recipientName, String phone, String postalCode,
                            String address1, String address2, String deliveryMemo,
                            String carrier, String trackingNumber, ShippingStatus status,
                            LocalDateTime shippedAt, LocalDateTime deliveredAt,
                            LocalDateTime updatedAt) {
        this.recipientName = recipientName;
        this.phone = phone;
        this.postalCode = postalCode;
        this.address1 = address1;
        this.address2 = address2;
        this.deliveryMemo = deliveryMemo;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
        this.updatedAt = updatedAt;
    }
}
