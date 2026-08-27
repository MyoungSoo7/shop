package github.lms.lemuel.marketing.application.port.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 럭키박스 화면에 필요한 전부. */
public record LuckyboxBoardView(
        UUID campaignId,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        String entryCondition,
        String benefitType,
        LocalDate benefitOn,
        String note,
        boolean drawableNow,
        boolean alreadyDrawnInSlot,
        String pcImageUrl,
        String mobileImageUrl,
        List<LuckyboxPrizeView> prizes,
        List<DrawResultView> myDraws
) {
}
