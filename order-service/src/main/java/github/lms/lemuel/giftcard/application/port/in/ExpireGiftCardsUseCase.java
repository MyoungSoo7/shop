package github.lms.lemuel.giftcard.application.port.in;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 기프트카드 소멸 유스케이스.
 *
 * <p>고객 재산을 지우는 배치라 {@code dryRun} 이 기본이다(포인트 소멸과 같은 규약).
 */
public interface ExpireGiftCardsUseCase {

    record ExpireGiftCardsCommand(OffsetDateTime at, int batchSize, boolean dryRun, String actor) {
    }

    record ExpireGiftCardsResult(int cardCount, BigDecimal forfeitedTotal, boolean dryRun) {
    }

    ExpireGiftCardsResult expire(ExpireGiftCardsCommand command);
}
