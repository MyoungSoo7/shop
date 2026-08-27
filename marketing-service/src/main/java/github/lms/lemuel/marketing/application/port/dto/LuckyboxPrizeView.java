package github.lms.lemuel.marketing.application.port.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 공개 경품 정보.
 *
 * <p>당첨 확률({@code winRate})과 잔여 수량은 <b>일부러 뺐다</b>. 레거시 JSP 는 아이템 목록을
 * 확률과 함께 그대로 뿌렸는데, 그러면 언제 뽑아야 유리한지가 밖에서 계산된다.
 * 운영자용 {@code /admin/promotions} 응답에는 들어 있다.
 */
public record LuckyboxPrizeView(
        UUID id,
        String prizeType,
        BigDecimal rewardPoints,
        String textReward,
        int displayOrder
) {
}
