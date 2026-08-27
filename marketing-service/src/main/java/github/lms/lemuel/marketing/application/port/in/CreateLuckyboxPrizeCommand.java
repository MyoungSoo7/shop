package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.domain.PrizeType;

import java.math.BigDecimal;
import java.util.UUID;

/** 경품 등록 명령. {@code totalQuota}/{@code dailyQuota} 가 null 이면 무제한이다. */
public record CreateLuckyboxPrizeCommand(
        UUID campaignId,
        PrizeType prizeType,
        BigDecimal rewardPoints,
        String textReward,
        Integer totalQuota,
        Integer dailyQuota,
        BigDecimal winRate,
        int displayOrder,
        String actor
) {
}
