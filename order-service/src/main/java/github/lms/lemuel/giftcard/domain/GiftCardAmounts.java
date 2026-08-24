package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardAmountException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 기프트카드 금액 규약 — 양수 + 1원 단위 정수.
 *
 * <p>포인트와 같은 규약이지만 도메인을 가로질러 공유하지 않는다. 공유하면 한쪽 정책이 바뀔 때
 * 다른 쪽이 함께 흔들리고, 그 결합은 두 원장을 나눠 둔 이유를 무효로 만든다.
 */
final class GiftCardAmounts {

    /** 저장 스케일 — 컬럼이 NUMERIC(19,2) 라 맞춘다. */
    static final int SCALE = 2;

    private GiftCardAmounts() {
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    static BigDecimal require(BigDecimal amount, String operation) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidGiftCardAmountException(
                    operation + " 금액은 양수여야 합니다: " + amount, operation, amount);
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new InvalidGiftCardAmountException(
                    operation + " 금액은 1원 단위 정수여야 합니다: " + amount, operation, amount);
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 이미 검증된 값(영속 복원 등)을 저장 스케일로만 맞춘다. */
    static BigDecimal normalize(BigDecimal value, String operation) {
        if (value == null) {
            throw new InvalidGiftCardAmountException("금액은 null 일 수 없습니다", operation, null);
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
