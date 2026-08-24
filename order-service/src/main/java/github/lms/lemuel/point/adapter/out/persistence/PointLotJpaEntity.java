package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** {@code point_lots} 매핑. */
@Entity
@Table(name = "point_lots")
public class PointLotJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 24)
    private PointLotOrigin origin;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PointLotStatus status;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PointLotJpaEntity() {
    }

    static PointLotJpaEntity from(PointLot lot) {
        PointLotJpaEntity entity = new PointLotJpaEntity();
        entity.id = lot.getId();
        entity.accountId = lot.getAccountId();
        entity.origin = lot.getOrigin();
        entity.originalAmount = lot.getOriginalAmount();
        entity.grantedAt = lot.getGrantedAt();
        entity.expiresAt = lot.getExpiresAt();
        entity.referenceType = lot.getReferenceType();
        entity.referenceId = lot.getReferenceId();
        entity.apply(lot);
        return entity;
    }

    void apply(PointLot lot) {
        this.remainingAmount = lot.getRemainingAmount();
        this.status = lot.getStatus();
    }

    PointLot toDomain() {
        return PointLot.rehydrate(id, accountId, origin, originalAmount, remainingAmount, status,
                grantedAt, expiresAt, referenceType, referenceId, version);
    }

    Long getId() {
        return id;
    }
}
