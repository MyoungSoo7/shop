package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.RewardStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 보상 지급 요청 영속 매핑 — 이 서비스가 order-service 에 요청한 포인트의 대장. */
@Entity
@Table(name = "reward_grants")
class RewardGrantJpaEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RewardSource source;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "member_ref", nullable = false, length = 64)
    private String memberRef;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(length = 300)
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RewardStatus status;

    @Column(name = "scheduled_on")
    private LocalDate scheduledOn;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected RewardGrantJpaEntity() {
    }

    static RewardGrantJpaEntity fromDomain(RewardGrant g) {
        RewardGrantJpaEntity e = new RewardGrantJpaEntity();
        e.id = g.id();
        e.source = g.source();
        e.referenceId = g.referenceId();
        e.campaignId = g.campaignId();
        e.memberRef = g.memberRef();
        e.amount = g.amount();
        e.expiresOn = g.expiresOn();
        e.memo = g.memo();
        e.scheduledOn = g.scheduledOn();
        e.createdAt = OffsetDateTime.now();
        e.sync(g);
        return e;
    }

    void sync(RewardGrant g) {
        this.status = g.status();
        this.requestedAt = g.requestedAt();
        this.confirmedAt = g.confirmedAt();
        this.failureReason = g.failureReason();
    }

    RewardGrant toDomain() {
        return RewardGrant.rehydrate(id, source, referenceId, campaignId, memberRef, amount, expiresOn, memo,
                status, scheduledOn, requestedAt, confirmedAt, failureReason, version);
    }
}
