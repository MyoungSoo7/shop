package github.lms.lemuel.marketing.application.port.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 출석체크 화면에 필요한 전부. */
public record AttendanceBoardView(
        UUID campaignId,
        String name,
        String periodType,
        String streakRule,
        String dayTypeRule,
        int requiredCount,
        LocalDate startsOn,
        LocalDate endsOn,
        LocalDate windowStart,
        LocalDate windowEnd,
        BigDecimal dailyRewardPoints,
        BigDecimal goalRewardPoints,
        int attendedTotal,
        int attendedStreak,
        int achievedCount,
        boolean checkedInToday,
        boolean eligibleToday,
        String message,
        String pcImageUrl,
        String mobileImageUrl,
        List<AttendanceDayView> days
) {
}
