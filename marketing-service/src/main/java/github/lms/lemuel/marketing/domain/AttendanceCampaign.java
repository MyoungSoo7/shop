package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import github.lms.lemuel.marketing.domain.exception.DayNotEligibleException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 출석체크 캠페인 애그리거트 루트.
 *
 * <p>참여 가능 여부·집계 구간·목표 달성 판정이 전부 여기 있다. 레거시에서는 같은 판단이
 * JSP({@code attendance.jsp} 의 스크립틀릿), 컨트롤러, MyBatis SQL 세 군데에 흩어져 있었고
 * 셋이 조금씩 달랐다 — 화면은 참여 버튼을 보여 주는데 서버가 거절하는 조합이 그래서 생겼다.
 *
 * <p>{@code version} 은 영속 계층의 낙관적 락 값을 실어 나르기만 한다.
 */
public final class AttendanceCampaign {

    private final UUID id;
    private final String tenantRef;
    private String name;
    private PeriodType periodType;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private StreakRule streakRule;
    private int requiredCount;
    private DayTypeRule dayTypeRule;
    private BigDecimal dailyRewardPoints;
    private BigDecimal goalRewardPoints;
    private LocalDate rewardExpiresFrom;
    private LocalDate rewardExpiresOn;
    private CampaignStatus status;
    private CampaignBanner banner;
    private AttendanceMessages messages;
    private final String createdBy;
    private String updatedBy;
    private final long version;

    private AttendanceCampaign(UUID id, String tenantRef, String name, PeriodType periodType,
                               LocalDate startsOn, LocalDate endsOn, StreakRule streakRule, int requiredCount,
                               DayTypeRule dayTypeRule, BigDecimal dailyRewardPoints, BigDecimal goalRewardPoints,
                               LocalDate rewardExpiresFrom, LocalDate rewardExpiresOn, CampaignStatus status,
                               CampaignBanner banner, AttendanceMessages messages,
                               String createdBy, String updatedBy, long version) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (startsOn == null || endsOn == null) throw new IllegalArgumentException("기간은 필수다");
        if (endsOn.isBefore(startsOn)) throw new IllegalArgumentException("종료일이 시작일보다 빠르다");
        if (requiredCount < 0) throw new IllegalArgumentException("목표 일수는 음수일 수 없다");
        // 목표가 있는 규칙인데 목표 일수가 0 이면 아무도 달성하지 못한다. 등록 시점에 막는다.
        if (streakRule != StreakRule.EVERY_DAY && requiredCount == 0) {
            throw new IllegalArgumentException("누적/연속 캠페인은 목표 일수가 1 이상이어야 한다");
        }
        this.id = id;
        this.tenantRef = tenantRef;
        this.name = name;
        this.periodType = periodType;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.streakRule = streakRule;
        this.requiredCount = requiredCount;
        this.dayTypeRule = dayTypeRule;
        this.dailyRewardPoints = nonNegative(dailyRewardPoints, "dailyRewardPoints");
        this.goalRewardPoints = nonNegative(goalRewardPoints, "goalRewardPoints");
        this.rewardExpiresFrom = rewardExpiresFrom;
        this.rewardExpiresOn = rewardExpiresOn;
        this.status = status;
        this.banner = banner == null ? CampaignBanner.empty() : banner;
        this.messages = messages == null ? AttendanceMessages.empty() : messages;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static AttendanceCampaign draft(UUID id, String tenantRef, String name, PeriodType periodType,
                                           LocalDate startsOn, LocalDate endsOn, StreakRule streakRule,
                                           int requiredCount, DayTypeRule dayTypeRule,
                                           BigDecimal dailyRewardPoints, BigDecimal goalRewardPoints,
                                           LocalDate rewardExpiresFrom, LocalDate rewardExpiresOn,
                                           CampaignBanner banner, AttendanceMessages messages, String actor) {
        return new AttendanceCampaign(id, tenantRef, name, periodType, startsOn, endsOn, streakRule, requiredCount,
                dayTypeRule, dailyRewardPoints, goalRewardPoints, rewardExpiresFrom, rewardExpiresOn,
                CampaignStatus.DRAFT, banner, messages, actor, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점. */
    public static AttendanceCampaign rehydrate(UUID id, String tenantRef, String name, PeriodType periodType,
                                               LocalDate startsOn, LocalDate endsOn, StreakRule streakRule,
                                               int requiredCount, DayTypeRule dayTypeRule,
                                               BigDecimal dailyRewardPoints, BigDecimal goalRewardPoints,
                                               LocalDate rewardExpiresFrom, LocalDate rewardExpiresOn,
                                               CampaignStatus status, CampaignBanner banner,
                                               AttendanceMessages messages, String createdBy, String updatedBy,
                                               long version) {
        return new AttendanceCampaign(id, tenantRef, name, periodType, startsOn, endsOn, streakRule, requiredCount,
                dayTypeRule, dailyRewardPoints, goalRewardPoints, rewardExpiresFrom, rewardExpiresOn, status,
                banner, messages, createdBy, updatedBy, version);
    }

    /**
     * 오늘 이 캠페인에 출석할 수 있는지 확인한다. 못 하면 던진다.
     *
     * <p>순서가 의미를 만든다 — 상태를 먼저 보고, 기간을 보고, 마지막에 요일 규칙을 본다.
     * 종료된 캠페인에 주말이라 안 된다고 답하면 사용자는 다음 주에 다시 온다.
     */
    public void assertCheckInAllowed(LocalDate today) {
        if (status != CampaignStatus.RUNNING) {
            throw new CampaignNotOpenException("진행 중인 캠페인이 아닙니다: " + name);
        }
        if (today.isBefore(startsOn) || today.isAfter(endsOn)) {
            throw new CampaignNotOpenException("이벤트 기간이 아닙니다: " + name);
        }
        if (!dayTypeRule.matches(today)) {
            throw new DayNotEligibleException("오늘은 출석 인정일이 아닙니다: " + name);
        }
    }

    /** {@code on} 이 속한 집계 구간(월간이면 그 달, 일간이면 캠페인 전체). */
    public LocalDate windowStart(LocalDate on) {
        return periodType.windowStart(startsOn, on);
    }

    public LocalDate windowEnd(LocalDate on) {
        return periodType.windowEnd(endsOn, on);
    }

    /** 이번 출석으로 목표를 새로 채웠는가. */
    public boolean goalReached(AttendanceStreak streak) {
        return streakRule.goalReached(streak, requiredCount);
    }

    public boolean hasDailyReward() {
        return dailyRewardPoints.signum() > 0;
    }

    public boolean hasGoalReward() {
        return goalRewardPoints.signum() > 0;
    }

    /**
     * 보상 포인트의 소멸일.
     *
     * <p>{@code rewardExpiresFrom} 이 있으면 그날 이후 지급분만 소멸일을 갖는다 — 레거시가
     * 이벤트 중간에 소멸 정책을 도입할 때 쓰던 방식이다. 조건에 안 맞으면 무기한 로트가 된다.
     */
    public LocalDate rewardExpiryFor(LocalDate grantedOn) {
        if (rewardExpiresOn == null) {
            return null;
        }
        if (rewardExpiresFrom != null && grantedOn.isBefore(rewardExpiresFrom)) {
            return null;
        }
        return rewardExpiresOn;
    }

    public void open(String actor) {
        if (status == CampaignStatus.CLOSED) {
            throw new CampaignNotOpenException("종료된 캠페인은 다시 열 수 없습니다: " + name);
        }
        this.status = CampaignStatus.RUNNING;
        this.updatedBy = actor;
    }

    public void close(String actor) {
        this.status = CampaignStatus.CLOSED;
        this.updatedBy = actor;
    }

    public void update(String name, LocalDate startsOn, LocalDate endsOn, BigDecimal dailyRewardPoints,
                       BigDecimal goalRewardPoints, CampaignBanner banner, AttendanceMessages messages,
                       String actor) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (startsOn == null || endsOn == null || endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("기간이 올바르지 않다");
        }
        // 집계 규칙(streakRule/dayTypeRule/periodType/requiredCount)은 여기서 못 바꾼다.
        // 이미 참여한 사람의 진행률이 소급해 달라지기 때문이다 — 바꾸려면 새 캠페인을 연다.
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.dailyRewardPoints = nonNegative(dailyRewardPoints, "dailyRewardPoints");
        this.goalRewardPoints = nonNegative(goalRewardPoints, "goalRewardPoints");
        this.banner = banner == null ? CampaignBanner.empty() : banner;
        this.messages = messages == null ? AttendanceMessages.empty() : messages;
        this.updatedBy = actor;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal resolved = value == null ? BigDecimal.ZERO : value;
        if (resolved.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return resolved;
    }

    public UUID id() { return id; }
    public String tenantRef() { return tenantRef; }
    public String name() { return name; }
    public PeriodType periodType() { return periodType; }
    public LocalDate startsOn() { return startsOn; }
    public LocalDate endsOn() { return endsOn; }
    public StreakRule streakRule() { return streakRule; }
    public int requiredCount() { return requiredCount; }
    public DayTypeRule dayTypeRule() { return dayTypeRule; }
    public BigDecimal dailyRewardPoints() { return dailyRewardPoints; }
    public BigDecimal goalRewardPoints() { return goalRewardPoints; }
    public LocalDate rewardExpiresFrom() { return rewardExpiresFrom; }
    public LocalDate rewardExpiresOn() { return rewardExpiresOn; }
    public CampaignStatus status() { return status; }
    public CampaignBanner banner() { return banner; }
    public AttendanceMessages messages() { return messages; }
    public String createdBy() { return createdBy; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
