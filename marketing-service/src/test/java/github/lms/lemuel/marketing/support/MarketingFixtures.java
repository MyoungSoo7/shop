package github.lms.lemuel.marketing.support;

import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.AttendanceMessages;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.CampaignBanner;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.PrizeType;
import github.lms.lemuel.marketing.domain.StreakRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 테스트 공용 고정물.
 *
 * <p>애그리거트 생성자 인자가 스무 개다. 테스트마다 그걸 늘어놓으면 "이 테스트가 무엇을
 * 확인하는지" 가 인자 더미에 묻히고, 필드 하나가 늘 때 테스트 수십 개가 같이 깨진다.
 * 여기서 기본값을 잡고, 각 테스트는 자기가 검사하는 값만 바꿔 넘긴다.
 */
public final class MarketingFixtures {

    public static final LocalDate START = LocalDate.of(2026, 8, 1);
    public static final LocalDate END = LocalDate.of(2026, 8, 31);
    public static final String MEMBER = "member-1";
    public static final String ACTOR = "admin";

    private MarketingFixtures() {
    }

    // ------------------------------------------------------------ 출석체크

    public static AttendanceCampaign attendance(CampaignStatus status, PeriodType periodType, StreakRule streakRule,
                                                int requiredCount, DayTypeRule dayTypeRule, BigDecimal daily,
                                                BigDecimal goal, LocalDate startsOn, LocalDate endsOn) {
        return AttendanceCampaign.rehydrate(UUID.randomUUID(), "tenant-1", "8월 출석", periodType,
                startsOn, endsOn, streakRule, requiredCount, dayTypeRule, daily, goal,
                null, null, status, CampaignBanner.of("pc.png", "mo.png"),
                new AttendanceMessages("곧 시작", "진행 중", "달성 축하", "종료"), ACTOR, ACTOR, 3L);
    }

    /** 진행 중, 전일 인정, 3일 연속 목표, 일일 10 / 목표 100 포인트. */
    public static AttendanceCampaign runningAttendance() {
        return attendance(CampaignStatus.RUNNING, PeriodType.DAILY, StreakRule.CONSECUTIVE, 3,
                DayTypeRule.EVERY_DAY, new BigDecimal("10"), new BigDecimal("100"), START, END);
    }

    // ------------------------------------------------------------ 럭키박스

    public static LuckyboxCampaign luckybox(CampaignStatus status, BenefitType benefitType, LocalDate benefitOn,
                                            EntryCondition entryCondition, LocalDate startsOn, LocalDate endsOn) {
        return LuckyboxCampaign.rehydrate(UUID.randomUUID(), "tenant-1", "8월 럭키박스", startsOn, endsOn, status,
                benefitType, benefitOn, entryCondition, LocalDate.of(2026, 12, 31), "1일 1회",
                CampaignBanner.of("pc.png", "mo.png"), ACTOR, ACTOR, 2L);
    }

    /** 진행 중, 즉시 지급, 하루 1회. */
    public static LuckyboxCampaign runningLuckybox() {
        return luckybox(CampaignStatus.RUNNING, BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY, START, END);
    }

    public static LuckyboxPrize pointPrize(UUID campaignId, String points, double winRate, int displayOrder) {
        return new LuckyboxPrize(UUID.randomUUID(), campaignId, PrizeType.POINT, new BigDecimal(points), null,
                null, null, BigDecimal.valueOf(winRate), 0, true, displayOrder, 0L);
    }

    public static LuckyboxPrize textPrize(UUID campaignId, String text, double winRate, int displayOrder) {
        return new LuckyboxPrize(UUID.randomUUID(), campaignId, PrizeType.TEXT, null, text,
                null, null, BigDecimal.valueOf(winRate), 0, true, displayOrder, 0L);
    }
}
