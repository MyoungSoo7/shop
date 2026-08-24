package github.lms.lemuel.giftcard.application.port.in;

import java.math.BigDecimal;

/**
 * 기프트카드 환불 복원 유스케이스 — 사용의 대칭.
 *
 * <p>복원 대상 카드는 커맨드에 없다. 원 사용 엔트리에서 도출한다 — 환불은 낸 사람에게 돌아가야
 * 하므로 호출자가 넘긴 식별자를 믿지 않는다(포인트 원장과 같은 판단).
 */
public interface RestoreGiftCardUseCase {

    record RestoreGiftCardCommand(BigDecimal amount,
                                  String sourceReferenceType, String sourceReferenceId,
                                  String referenceType, String referenceId, String actor) {
    }

    record RestoreGiftCardResult(BigDecimal restoredAmount, int cardCount) {
    }

    RestoreGiftCardResult restore(RestoreGiftCardCommand command);
}
