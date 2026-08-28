package github.lms.lemuel.marketing.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 럭키박스 경품 (레거시 {@code TBL_LUCKYBOX_ITEM}).
 *
 * <p>{@code winRate} 는 확률이 아니라 <b>가중치</b>다. 합이 1 이나 100 일 필요가 없고, 추첨은
 * 활성 경품들의 가중치 합으로 정규화한다(레거시와 같은 동작). 경품을 하나 비활성화해도 남은
 * 경품들의 상대 비율이 유지된다는 뜻이다.
 *
 * <p>수량은 두 층이다. {@code totalQuota} 는 캠페인 전체, {@code dailyQuota} 는 하루치.
 * 레거시에는 둘 다 컬럼만 있고 확인 코드가 없었다 — {@code // 아이템 수량 확인} 주석 아래가
 * 비어 있었다. 그래서 "선착순 100명" 경품이 몇 명에게 나갔는지 아무도 몰랐다.
 */
public record LuckyboxPrize(
        UUID id,
        UUID campaignId,
        PrizeType prizeType,
        BigDecimal rewardPoints,
        String textReward,
        Integer totalQuota,
        Integer dailyQuota,
        BigDecimal winRate,
        int issuedCount,
        boolean active,
        int displayOrder,
        long version
) {

    public LuckyboxPrize {
        if (id == null || campaignId == null) throw new IllegalArgumentException("id/campaignId is required");
        if (prizeType == null) throw new IllegalArgumentException("prizeType is required");
        if (winRate == null || winRate.signum() < 0) throw new IllegalArgumentException("winRate must not be negative");
        if (prizeType == PrizeType.POINT && (rewardPoints == null || rewardPoints.signum() <= 0)) {
            // 0 포인트 당첨은 사용자에게 "당첨"으로 보이고 잔액은 그대로다. 등록 시점에 막는다.
            throw new IllegalArgumentException("포인트 경품은 지급 포인트가 1 이상이어야 한다");
        }
        if (totalQuota != null && totalQuota < 0) throw new IllegalArgumentException("totalQuota must not be negative");
        if (dailyQuota != null && dailyQuota < 0) throw new IllegalArgumentException("dailyQuota must not be negative");
    }

    /** 전체 수량이 남았는가. {@code totalQuota} 가 null 이면 무제한이다. */
    public boolean hasTotalQuotaLeft() {
        return totalQuota == null || issuedCount < totalQuota;
    }

    /**
     * 추첨 후보에 오를 수 있는가.
     *
     * <p>일일 수량은 여기서 보지 않는다 — 날짜별 소진량은 별도 테이블에 있고, 후보 선정이 아니라
     * 예약(조건부 UPDATE) 단계에서 확인한다. 후보 단계에서 미리 조회하면 조회와 예약 사이에
     * 창이 생겨서, 마지막 한 개를 두 사람이 동시에 받는다.
     */
    public boolean isDrawable() {
        return active && winRate.signum() > 0 && hasTotalQuotaLeft();
    }

    /** 이 경품이 포인트 보상을 만드는가. */
    public boolean grantsPoints() {
        return prizeType.grantsPoints() && rewardPoints != null && rewardPoints.signum() > 0;
    }
}
