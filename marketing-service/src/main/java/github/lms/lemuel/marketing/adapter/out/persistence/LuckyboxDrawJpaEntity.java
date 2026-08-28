package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.LuckyboxDraw;
import github.lms.lemuel.marketing.domain.PrizeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 럭키박스 참여·당첨 기록 영속 매핑. */
@Entity
@Table(name = "luckybox_draws")
class LuckyboxDrawJpaEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "member_ref", nullable = false, length = 64)
    private String memberRef;

    @Column(name = "prize_id")
    private UUID prizeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prize_type", nullable = false, length = 16)
    private PrizeType prizeType;

    @Column(name = "reward_points", precision = 19, scale = 2)
    private BigDecimal rewardPoints;

    @Column(name = "text_reward", length = 200)
    private String textReward;

    @Column(name = "drawn_on", nullable = false)
    private LocalDate drawnOn;

    @Column(name = "drawn_at", nullable = false)
    private Instant drawnAt;

    @Column(name = "entry_slot", nullable = false, length = 16)
    private String entrySlot;

    protected LuckyboxDrawJpaEntity() {
    }

    static LuckyboxDrawJpaEntity fromDomain(LuckyboxDraw d) {
        LuckyboxDrawJpaEntity e = new LuckyboxDrawJpaEntity();
        e.id = d.id();
        e.campaignId = d.campaignId();
        e.memberRef = d.memberRef();
        e.prizeId = d.prizeId();
        e.prizeType = d.prizeType();
        e.rewardPoints = d.rewardPoints();
        e.textReward = d.textReward();
        e.drawnOn = d.drawnOn();
        e.drawnAt = d.drawnAt();
        e.entrySlot = d.entrySlot();
        return e;
    }

    LuckyboxDraw toDomain() {
        return new LuckyboxDraw(id, campaignId, memberRef, prizeId, prizeType, rewardPoints, textReward,
                drawnOn, drawnAt, entrySlot);
    }
}
