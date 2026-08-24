package github.lms.lemuel.giftcard.application.port.in;

import java.math.BigDecimal;

/**
 * 기프트카드 사용(차감) 유스케이스 — 결제의 GIFT_CARD 텐더가 부르는 진입점.
 */
public interface UseGiftCardUseCase {

    /**
     * @param userId 카드 소유자 — JWT 주체에서 파생(요청 본문을 믿지 마라)
     */
    record UseGiftCardCommand(Long userId, BigDecimal amount, String referenceType,
                              String referenceId, String actor) {
    }

    record UseGiftCardResult(BigDecimal usedAmount, int cardCount, BigDecimal remainingBalance) {
    }

    UseGiftCardResult use(UseGiftCardCommand command);
}
