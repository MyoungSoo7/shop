package github.lms.lemuel.giftcard.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * 기프트카드 발행 유스케이스(관리자).
 *
 * <p>발행 응답은 <b>평문 코드를 담는 유일한 순간</b>이다. 이후 어떤 조회로도 코드를 다시 얻을 수
 * 없다 — 저장된 것은 해시뿐이기 때문이다. 그래서 응답을 놓치면 그 카드는 배포할 수 없다.
 */
public interface IssueGiftCardsUseCase {

    /**
     * @param quantity     발행 장수
     * @param faceAmount   권면가(양수 정수)
     * @param validityDays 유효기간 일수
     * @param activate     true 면 발행 즉시 활성화(등록 가능 상태)
     */
    record IssueGiftCardsCommand(int quantity, BigDecimal faceAmount, int validityDays,
                                 boolean activate, String actor, String memo) {
    }

    /** {@code code} 는 이 응답에서만 볼 수 있다. */
    record IssuedGiftCard(Long giftCardId, String code, String codeLast4, BigDecimal faceAmount) {
    }

    List<IssuedGiftCard> issue(IssueGiftCardsCommand command);
}
