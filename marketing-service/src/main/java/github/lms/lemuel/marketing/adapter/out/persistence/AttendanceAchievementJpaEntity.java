package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.AttendanceAchievement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 목표 달성 기록 영속 매핑. */
@Entity
@Table(name = "attendance_achievements")
class AttendanceAchievementJpaEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "member_ref", nullable = false, length = 64)
    private String memberRef;

    @Column(name = "achieved_on", nullable = false)
    private LocalDate achievedOn;

    @Column(name = "reward_points", nullable = false, precision = 19, scale = 2)
    private BigDecimal rewardPoints;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AttendanceAchievementJpaEntity() {
    }

    static AttendanceAchievementJpaEntity fromDomain(AttendanceAchievement a) {
        AttendanceAchievementJpaEntity e = new AttendanceAchievementJpaEntity();
        e.id = a.id();
        e.campaignId = a.campaignId();
        e.memberRef = a.memberRef();
        e.achievedOn = a.achievedOn();
        e.rewardPoints = a.rewardPoints();
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    AttendanceAchievement toDomain() {
        return new AttendanceAchievement(id, campaignId, memberRef, achievedOn, rewardPoints);
    }
}
