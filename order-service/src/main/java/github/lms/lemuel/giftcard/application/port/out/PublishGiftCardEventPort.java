package github.lms.lemuel.giftcard.application.port.out;

import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;

import java.math.BigDecimal;

/**
 * 기프트카드 도메인 이벤트 발행 포트 — 구현은 Outbox 어댑터다(직접 send 금지).
 *
 * <p>소비자는 {@code account-service} 다. 미사용 상품권 잔액은 회사의 <b>부채</b>이고 무상 발행분은
 * <b>판촉비</b>라, 잔액 변화가 GL 로 넘어가지 않으면 시산표가 현실과 어긋난다.
 *
 * <p><b>발행·활성화에는 이벤트가 없다.</b> 아직 아무에게도 가지 않은 코드는 빚이 아니다.
 */
public interface PublishGiftCardEventPort {

    /** 등록 — DR GIFT_CARD_PROMOTION_EXPENSE / CR GIFT_CARD_LIABILITY. 부채가 생기는 유일한 지점. */
    void giftCardRegistered(GiftCard card, GiftCardEntry entry);

    /** 사용 — DR GIFT_CARD_LIABILITY / CR CASH. 정산이 가정한 현금 유입을 상계한다. */
    void giftCardUsed(GiftCard card, GiftCardEntry entry);

    /** 환불 복원 — DR CASH / CR GIFT_CARD_LIABILITY (사용의 대칭). */
    void giftCardRestored(GiftCard card, GiftCardEntry entry);

    /** 소멸 — DR GIFT_CARD_LIABILITY / CR GIFT_CARD_BREAKAGE_INCOME. */
    void giftCardExpired(GiftCard card, BigDecimal forfeitedAmount);
}
