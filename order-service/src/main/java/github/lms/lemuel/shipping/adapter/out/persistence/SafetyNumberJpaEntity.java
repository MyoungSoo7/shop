package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.SafetyNumber;
import github.lms.lemuel.shipping.domain.SafetyNumberStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "safety_numbers")
public class SafetyNumberJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "virtual_number", nullable = false, unique = true, length = 20)
    private String virtualNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SafetyNumberStatus status;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    protected SafetyNumberJpaEntity() {
    }

    void apply(SafetyNumber domain) {
        this.virtualNumber = domain.getVirtualNumber();
        this.status = domain.getStatus();
        this.orderId = domain.getOrderId();
        this.assignedAt = domain.getAssignedAt();
        this.expiresAt = domain.getExpiresAt();
    }

    static SafetyNumberJpaEntity from(SafetyNumber domain) {
        SafetyNumberJpaEntity entity = new SafetyNumberJpaEntity();
        entity.id = domain.getId();
        entity.apply(domain);
        return entity;
    }

    SafetyNumber toDomain() {
        return SafetyNumber.rehydrate(id, virtualNumber, status, orderId, assignedAt, expiresAt);
    }

    public Long getId() { return id; }
}
