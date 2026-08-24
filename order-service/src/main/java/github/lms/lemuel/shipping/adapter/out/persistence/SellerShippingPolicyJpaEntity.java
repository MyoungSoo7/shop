package github.lms.lemuel.shipping.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 셀러 배송비 정책 행. PK 가 seller_id 라 셀러당 정확히 하나만 존재한다(중복 정책으로 배송비가
 * 두 갈래로 갈리는 상황을 스키마가 원천 차단).
 */
@Entity
@Table(name = "seller_shipping_policies")
public class SellerShippingPolicyJpaEntity {

    @Id
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "base_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal baseFee;

    /** NULL 이면 무료배송 조건 없음 — 0(항상 무료)과 다른 의미라 NOT NULL 로 뭉개지 않는다. */
    @Column(name = "free_threshold", precision = 19, scale = 2)
    private BigDecimal freeThreshold;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SellerShippingPolicyJpaEntity() {
    }

    public SellerShippingPolicyJpaEntity(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        this.sellerId = sellerId;
        this.baseFee = baseFee;
        this.freeThreshold = freeThreshold;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (baseFee == null) {
            baseFee = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** 정책 값 갱신 — 식별자(seller_id)는 바뀌지 않는다. */
    public void applyChange(BigDecimal newBaseFee, BigDecimal newFreeThreshold) {
        this.baseFee = newBaseFee;
        this.freeThreshold = newFreeThreshold;
    }

    public Long getSellerId() { return sellerId; }
    public BigDecimal getBaseFee() { return baseFee; }
    public BigDecimal getFreeThreshold() { return freeThreshold; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
