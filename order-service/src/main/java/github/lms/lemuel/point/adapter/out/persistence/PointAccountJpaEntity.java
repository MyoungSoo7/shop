package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
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

/** {@code point_accounts} 매핑. 도메인({@link PointAccount})과의 변환만 담당한다. */
@Entity
@Table(name = "point_accounts")
public class PointAccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "available", nullable = false, precision = 19, scale = 2)
    private BigDecimal available;

    @Column(name = "locked", nullable = false, precision = 19, scale = 2)
    private BigDecimal locked;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PointAccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PointAccountJpaEntity() {
    }

    static PointAccountJpaEntity from(PointAccount account) {
        PointAccountJpaEntity entity = new PointAccountJpaEntity();
        entity.id = account.getId();
        entity.apply(account);
        entity.createdAt = account.getCreatedAt();
        return entity;
    }

    void apply(PointAccount account) {
        this.userId = account.getUserId();
        this.available = account.getAvailable();
        this.locked = account.getLocked();
        this.total = account.getTotal();
        this.status = account.getStatus();
        this.updatedAt = account.getUpdatedAt();
    }

    PointAccount toDomain() {
        return PointAccount.rehydrate(id, userId, available, locked, total, status,
                version, createdAt, updatedAt);
    }

    Long getId() {
        return id;
    }

    long getVersion() {
        return version;
    }
}
