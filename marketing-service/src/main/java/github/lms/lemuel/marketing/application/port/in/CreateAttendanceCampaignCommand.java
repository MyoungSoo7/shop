package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.StreakRule;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 출석 캠페인 등록 명령. */
public record CreateAttendanceCampaignCommand(
        String tenantRef,
        String name,
        PeriodType periodType,
        LocalDate startsOn,
        LocalDate endsOn,
        StreakRule streakRule,
        int requiredCount,
        DayTypeRule dayTypeRule,
        BigDecimal dailyRewardPoints,
        BigDecimal goalRewardPoints,
        LocalDate rewardExpiresFrom,
        LocalDate rewardExpiresOn,
        String pcImageUrl,
        String mobileImageUrl,
        String messageBeforeStart,
        String messageRunning,
        String messageAchieved,
        String messageClosed,
        String actor
) {
}
