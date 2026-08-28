package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.CampaignBanner;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 럭키박스 캠페인 영속 매핑. */
@Entity
@Table(name = "luckybox_campaigns")
class LuckyboxCampaignJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_ref", length = 32)
    private String tenantRef;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false, length = 16)
    private BenefitType benefitType;

    @Column(name = "benefit_on")
    private LocalDate benefitOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_condition", nullable = false, length = 16)
    private EntryCondition entryCondition;

    @Column(name = "reward_expires_on")
    private LocalDate rewardExpiresOn;

    @Column
    private String note;

    @Column(name = "pc_image_url", length = 500)
    private String pcImageUrl;

    @Column(name = "mobile_image_url", length = 500)
    private String mobileImageUrl;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected LuckyboxCampaignJpaEntity() {
    }

    static LuckyboxCampaignJpaEntity fromDomain(LuckyboxCampaign c) {
        LuckyboxCampaignJpaEntity e = new LuckyboxCampaignJpaEntity();
        e.id = c.id();
        e.tenantRef = c.tenantRef();
        e.benefitType = c.benefitType();
        e.entryCondition = c.entryCondition();
        e.createdBy = c.createdBy();
        e.createdAt = OffsetDateTime.now();
        e.sync(c);
        return e;
    }

    void sync(LuckyboxCampaign c) {
        this.name = c.name();
        this.startsOn = c.startsOn();
        this.endsOn = c.endsOn();
        this.status = c.status();
        this.benefitOn = c.benefitOn();
        this.rewardExpiresOn = c.rewardExpiresOn();
        this.note = c.note();
        this.pcImageUrl = c.banner().pcImageUrl();
        this.mobileImageUrl = c.banner().mobileImageUrl();
        this.updatedBy = c.updatedBy();
        this.updatedAt = OffsetDateTime.now();
    }

    LuckyboxCampaign toDomain() {
        return LuckyboxCampaign.rehydrate(id, tenantRef, name, startsOn, endsOn, status, benefitType, benefitOn,
                entryCondition, rewardExpiresOn, note, CampaignBanner.of(pcImageUrl, mobileImageUrl),
                createdBy, updatedBy, version);
    }
}
