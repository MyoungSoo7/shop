package github.lms.lemuel.marketing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 하루치 출석 기록 (레거시 {@code TBL_ATTENDANCE_APPLY}).
 *
 * <p>스냅샷 네 개(캠페인명·집계 규칙·구간)를 같이 남긴다. 레거시가 {@code EVENT_HISTORY_*} 로
 * 하던 것을 그대로 가져왔다 — 운영자가 캠페인 문구나 기간을 고치고 나면 "그때 어떤 조건으로
 * 참여한 건지" 를 캠페인 행에서 되살릴 수 없어서, 문의가 들어오면 답할 근거가 없어진다.
 */
public record AttendanceRecord(
        UUID id,
        UUID campaignId,
        String memberRef,
        LocalDate attendedOn,
        BigDecimal dailyRewardPoints,
        String campaignNameSnapshot,
        StreakRule streakRuleSnapshot,
        LocalDate periodStartSnapshot,
        LocalDate periodEndSnapshot
) {

    public AttendanceRecord {
        if (id == null || campaignId == null) throw new IllegalArgumentException("id/campaignId is required");
        if (memberRef == null || memberRef.isBlank()) throw new IllegalArgumentException("memberRef is required");
        if (attendedOn == null) throw new IllegalArgumentException("attendedOn is required");
        if (dailyRewardPoints == null || dailyRewardPoints.signum() < 0) {
            throw new IllegalArgumentException("dailyRewardPoints must not be negative");
        }
    }

    public static AttendanceRecord of(UUID id, AttendanceCampaign campaign, String memberRef, LocalDate attendedOn) {
        return new AttendanceRecord(
                id,
                campaign.id(),
                memberRef,
                attendedOn,
                campaign.dailyRewardPoints(),
                campaign.name(),
                campaign.streakRule(),
                campaign.windowStart(attendedOn),
                campaign.windowEnd(attendedOn));
    }
}
