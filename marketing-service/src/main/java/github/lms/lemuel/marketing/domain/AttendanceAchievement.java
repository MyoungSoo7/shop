package github.lms.lemuel.marketing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 목표 달성 기록 (레거시 {@code TBL_ATTENDANCE_SUCCESS}).
 *
 * <p>목표는 기간 안에서 여러 번 달성될 수 있다 — 5일 연속이 목표인데 10일을 오면 두 번이다.
 * 하지만 하루에 두 번은 없다. 그 경계를 {@code (campaign_id, member_ref, achieved_on)}
 * 유니크 인덱스가 잡는다.
 */
public record AttendanceAchievement(
        UUID id,
        UUID campaignId,
        String memberRef,
        LocalDate achievedOn,
        BigDecimal rewardPoints
) {

    public AttendanceAchievement {
        if (id == null || campaignId == null) throw new IllegalArgumentException("id/campaignId is required");
        if (memberRef == null || memberRef.isBlank()) throw new IllegalArgumentException("memberRef is required");
        if (achievedOn == null) throw new IllegalArgumentException("achievedOn is required");
        if (rewardPoints == null || rewardPoints.signum() < 0) {
            throw new IllegalArgumentException("rewardPoints must not be negative");
        }
    }

    public static AttendanceAchievement of(UUID id, AttendanceCampaign campaign, String memberRef, LocalDate on) {
        return new AttendanceAchievement(id, campaign.id(), memberRef, on, campaign.goalRewardPoints());
    }
}
