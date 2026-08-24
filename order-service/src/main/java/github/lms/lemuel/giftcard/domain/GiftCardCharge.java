package github.lms.lemuel.giftcard.domain;

import java.math.BigDecimal;

/**
 * 한 번의 사용이 카드 하나에서 얼마를 썼는지.
 *
 * <p>결제 1건이 여러 장을 걸치면 이 값이 여러 개 나오고, 각각이 원장 엔트리 하나가 된다.
 * 환불 복원은 이 상세를 되짚어 "원래 그 카드"로 돌려준다.
 *
 * @param giftCardId 사용된 카드
 * @param amount     그 카드에서 쓴 금액(양수)
 */
public record GiftCardCharge(Long giftCardId, BigDecimal amount) {
}
