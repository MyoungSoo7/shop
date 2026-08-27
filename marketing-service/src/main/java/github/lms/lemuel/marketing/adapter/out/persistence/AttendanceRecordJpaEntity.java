package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.AttendanceRecord;
import github.lms.lemuel.marketing.domain.StreakRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 출석 기록 영속 매핑. 참여 시점 조건 스냅샷을 함께 들고 있다. */
@Entity
@Table(name = "attendance_records")
class AttendanceRecordJpaEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "member_ref", nullable = false, length = 64)
    private String memberRef;

    @Column(name = "attended_on", nullable = false)
    private LocalDate attendedOn;

    @Column(name = "daily_reward_points", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyRewardPoints;

    @Column(name = "campaign_name_snapshot", nullable = false, length = 200)
    private String campaignNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "streak_rule_snapshot", nullable = false, length = 16)
    private StreakRule streakRuleSnapshot;

    @Column(name = "period_start_snapshot", nullable = false)
    private LocalDate periodStartSnapshot;

    @Column(name = "period_end_snapshot", nullable = false)
    private LocalDate periodEndSnapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AttendanceRecordJpaEntity() {
    }

    static AttendanceRecordJpaEntity fromDomain(AttendanceRecord r) {
        AttendanceRecordJpaEntity e = new AttendanceRecordJpaEntity();
        e.id = r.id();
        e.campaignId = r.campaignId();
        e.memberRef = r.memberRef();
        e.attendedOn = r.attendedOn();
        e.dailyRewardPoints = r.dailyRewardPoints();
        e.campaignNameSnapshot = r.campaignNameSnapshot();
        e.streakRuleSnapshot = r.streakRuleSnapshot();
        e.periodStartSnapshot = r.periodStartSnapshot();
        e.periodEndSnapshot = r.periodEndSnapshot();
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    AttendanceRecord toDomain() {
        return new AttendanceRecord(id, campaignId, memberRef, attendedOn, dailyRewardPoints,
                campaignNameSnapshot, streakRuleSnapshot, periodStartSnapshot, periodEndSnapshot);
    }
}
