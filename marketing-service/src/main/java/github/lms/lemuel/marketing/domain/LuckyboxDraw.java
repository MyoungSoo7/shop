package github.lms.lemuel.marketing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 럭키박스 참여·당첨 기록 (레거시 {@code TBL_LUCKYBOX_APPLY}).
 *
 * <p>경품 내용을 참조가 아니라 값으로도 복사해 둔다. 경품 행은 나중에 수정되거나 비활성화되지만
 * "이 사람이 무엇에 당첨됐는지" 는 그 시점 그대로 남아야 한다.
 */
public record LuckyboxDraw(
        UUID id,
        UUID campaignId,
        String memberRef,
        UUID prizeId,
        PrizeType prizeType,
        BigDecimal rewardPoints,
        String textReward,
        LocalDate drawnOn,
        Instant drawnAt,
        String entrySlot
) {

    public LuckyboxDraw {
        if (id == null || campaignId == null) throw new IllegalArgumentException("id/campaignId is required");
        if (memberRef == null || memberRef.isBlank()) throw new IllegalArgumentException("memberRef is required");
        if (prizeType == null) throw new IllegalArgumentException("prizeType is required");
        if (drawnOn == null) throw new IllegalArgumentException("drawnOn is required");
        if (entrySlot == null || entrySlot.isBlank()) throw new IllegalArgumentException("entrySlot is required");
    }

    public static LuckyboxDraw of(UUID id, LuckyboxCampaign campaign, String memberRef,
                                  LuckyboxPrize prize, LocalDate on) {
        return new LuckyboxDraw(
                id,
                campaign.id(),
                memberRef,
                prize.id(),
                prize.prizeType(),
                prize.rewardPoints(),
                prize.textReward(),
                on,
                Instant.now(),
                campaign.entrySlot(on));
    }

    public boolean grantsPoints() {
        return prizeType.grantsPoints() && rewardPoints != null && rewardPoints.signum() > 0;
    }
}
