package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.PrizeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 경품 영속 매핑.
 *
 * <p>수량 카운터 세 개({@code issuedCount}, {@code dailyIssuedCount}, {@code dailyIssuedDate})는
 * <b>{@link #sync} 가 건드리지 않는다.</b> 예약은 벌크 UPDATE 한 문장으로 이 컬럼들을 올리는데,
 * JPA 벌크 UPDATE 는 {@code @Version} 을 올리지 않는다. 즉 낙관적 잠금이 이 세 컬럼을 보호해 주지
 * 못한다 — 운영자가 화면에서 경품 이름만 고쳐 저장해도, 그 사이 예약된 수량이 저장 시점의 옛 값으로
 * 덮여 "선착순 100개" 가 조용히 다시 채워진다. 그래서 도메인이 들고 온 카운터 값은 무시하고
 * DB 에 있는 값을 그대로 둔다. 새로 만드는 행({@link #fromDomain})에서만 0 으로 시작한다.
 */
@Entity
@Table(name = "luckybox_prizes")
class LuckyboxPrizeJpaEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prize_type", nullable = false, length = 16)
    private PrizeType prizeType;

    @Column(name = "reward_points", precision = 19, scale = 2)
    private BigDecimal rewardPoints;

    @Column(name = "text_reward", length = 200)
    private String textReward;

    @Column(name = "total_quota")
    private Integer totalQuota;

    @Column(name = "daily_quota")
    private Integer dailyQuota;

    @Column(name = "win_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal winRate;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Column(name = "daily_issued_count", nullable = false)
    private int dailyIssuedCount;

    @Column(name = "daily_issued_date")
    private LocalDate dailyIssuedDate;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected LuckyboxPrizeJpaEntity() {
    }

    static LuckyboxPrizeJpaEntity fromDomain(LuckyboxPrize p) {
        LuckyboxPrizeJpaEntity e = new LuckyboxPrizeJpaEntity();
        e.id = p.id();
        e.campaignId = p.campaignId();
        e.issuedCount = 0;
        e.dailyIssuedCount = 0;
        e.dailyIssuedDate = null;
        e.createdAt = OffsetDateTime.now();
        e.sync(p);
        return e;
    }

    /** 설정값만 반영한다 — 수량 카운터는 클래스 주석의 이유로 일부러 뺐다. */
    void sync(LuckyboxPrize p) {
        this.prizeType = p.prizeType();
        this.rewardPoints = p.rewardPoints();
        this.textReward = p.textReward();
        this.totalQuota = p.totalQuota();
        this.dailyQuota = p.dailyQuota();
        this.winRate = p.winRate();
        this.active = p.active();
        this.displayOrder = p.displayOrder();
        this.updatedAt = OffsetDateTime.now();
    }

    LuckyboxPrize toDomain() {
        return new LuckyboxPrize(id, campaignId, prizeType, rewardPoints, textReward, totalQuota, dailyQuota,
                winRate, issuedCount, active, displayOrder, version);
    }
}
