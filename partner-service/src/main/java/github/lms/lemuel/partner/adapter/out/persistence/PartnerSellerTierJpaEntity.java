package github.lms.lemuel.partner.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** {@code partner_seller_tiers} 매핑 — <b>현재</b> 등급 스냅샷. 과거 재계산에 쓰지 않는다. */
@Entity
@Table(name = "partner_seller_tiers")
class PartnerSellerTierJpaEntity {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "current_tier", nullable = false, length = 20)
    private String currentTier;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false, length = 30)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PartnerSellerTierJpaEntity() {
    }
}
