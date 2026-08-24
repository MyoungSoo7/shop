package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointUsageLimit;
import github.lms.lemuel.point.domain.PointUsageLimitType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 포인트 사용 상한 정책 — 단일 행(id=1). 행이 하나뿐임은 DB CHECK 가 강제한다. */
@Entity
@Table(name = "point_usage_limit_policy")
public class PointUsageLimitPolicyJpaEntity {

    static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(name = "limit_type", nullable = false, length = 20)
    private PointUsageLimitType limitType;

    @Column(name = "limit_amount", precision = 19, scale = 2)
    private BigDecimal limitAmount;

    @Column(name = "limit_ratio_percent", precision = 5, scale = 2)
    private BigDecimal limitRatioPercent;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    protected PointUsageLimitPolicyJpaEntity() {
    }

    static PointUsageLimitPolicyJpaEntity singleton(PointUsageLimit limit, String actor) {
        PointUsageLimitPolicyJpaEntity entity = new PointUsageLimitPolicyJpaEntity();
        entity.id = SINGLETON_ID;
        entity.apply(limit, actor);
        return entity;
    }

    void apply(PointUsageLimit limit, String actor) {
        this.limitType = limit.getType();
        this.limitAmount = limit.getLimitAmount();
        this.limitRatioPercent = limit.getLimitRatioPercent();
        this.updatedBy = actor;
        this.updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    PointUsageLimit toDomain() {
        return PointUsageLimit.rehydrate(limitType, limitAmount, limitRatioPercent);
    }

    public String getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
