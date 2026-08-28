package github.lms.lemuel.marketing.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 출석 캠페인 수정 명령.
 *
 * <p>집계 규칙(periodType/streakRule/dayTypeRule/requiredCount)은 여기 없다 — 이미 참여한
 * 사람의 진행률이 소급해 달라지기 때문이다. 규칙을 바꾸려면 새 캠페인을 연다.
 */
public record UpdateAttendanceCampaignCommand(
        UUID campaignId,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        BigDecimal dailyRewardPoints,
        BigDecimal goalRewardPoints,
        String pcImageUrl,
        String mobileImageUrl,
        String messageBeforeStart,
        String messageRunning,
        String messageAchieved,
        String messageClosed,
        String actor
) {
}
