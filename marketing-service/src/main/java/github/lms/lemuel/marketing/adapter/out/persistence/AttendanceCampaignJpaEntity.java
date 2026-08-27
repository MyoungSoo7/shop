package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.AttendanceMessages;
import github.lms.lemuel.marketing.domain.CampaignBanner;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.StreakRule;
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
 * 출석 캠페인 영속 매핑. 규칙은 하나도 없다 — 전부 {@link AttendanceCampaign} 에 있다.
 *
 * <p>enum 을 {@code EnumType.STRING} 으로 저장한다. ORDINAL 은 enum 상수 순서를 바꾸는 순간
 * 이미 저장된 행의 의미가 통째로 밀린다 — 레거시가 코드값('N'/'Y'/'C')을 쓴 것보다 더 나쁘다.
 */
@Entity
@Table(name = "attendance_campaigns")
class AttendanceCampaignJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_ref", length = 32)
    private String tenantRef;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 16)
    private PeriodType periodType;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "streak_rule", nullable = false, length = 16)
    private StreakRule streakRule;

    @Column(name = "required_count", nullable = false)
    private int requiredCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type_rule", nullable = false, length = 8)
    private DayTypeRule dayTypeRule;

    @Column(name = "daily_reward_points", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyRewardPoints;

    @Column(name = "goal_reward_points", nullable = false, precision = 19, scale = 2)
    private BigDecimal goalRewardPoints;

    @Column(name = "reward_expires_from")
    private LocalDate rewardExpiresFrom;

    @Column(name = "reward_expires_on")
    private LocalDate rewardExpiresOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status;

    @Column(name = "pc_image_url", length = 500)
    private String pcImageUrl;

    @Column(name = "mobile_image_url", length = 500)
    private String mobileImageUrl;

    @Column(name = "message_before")
    private String messageBefore;

    @Column(name = "message_running")
    private String messageRunning;

    @Column(name = "message_achieved")
    private String messageAchieved;

    @Column(name = "message_closed")
    private String messageClosed;

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

    protected AttendanceCampaignJpaEntity() {
    }

    static AttendanceCampaignJpaEntity fromDomain(AttendanceCampaign c) {
        AttendanceCampaignJpaEntity e = new AttendanceCampaignJpaEntity();
        e.id = c.id();
        e.tenantRef = c.tenantRef();
        e.createdBy = c.createdBy();
        e.createdAt = OffsetDateTime.now();
        e.sync(c);
        return e;
    }

    void sync(AttendanceCampaign c) {
        this.name = c.name();
        this.periodType = c.periodType();
        this.startsOn = c.startsOn();
        this.endsOn = c.endsOn();
        this.streakRule = c.streakRule();
        this.requiredCount = c.requiredCount();
        this.dayTypeRule = c.dayTypeRule();
        this.dailyRewardPoints = c.dailyRewardPoints();
        this.goalRewardPoints = c.goalRewardPoints();
        this.rewardExpiresFrom = c.rewardExpiresFrom();
        this.rewardExpiresOn = c.rewardExpiresOn();
        this.status = c.status();
        this.pcImageUrl = c.banner().pcImageUrl();
        this.mobileImageUrl = c.banner().mobileImageUrl();
        this.messageBefore = c.messages().beforeStart();
        this.messageRunning = c.messages().running();
        this.messageAchieved = c.messages().achieved();
        this.messageClosed = c.messages().closed();
        this.updatedBy = c.updatedBy();
        this.updatedAt = OffsetDateTime.now();
    }

    AttendanceCampaign toDomain() {
        return AttendanceCampaign.rehydrate(id, tenantRef, name, periodType, startsOn, endsOn, streakRule,
                requiredCount, dayTypeRule, dailyRewardPoints, goalRewardPoints, rewardExpiresFrom, rewardExpiresOn,
                status, CampaignBanner.of(pcImageUrl, mobileImageUrl),
                new AttendanceMessages(messageBefore, messageRunning, messageAchieved, messageClosed),
                createdBy, updatedBy, version);
    }
}
