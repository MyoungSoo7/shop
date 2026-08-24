package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 포인트 금액 규약 — 이 도메인에서 금액이 지켜야 할 두 가지를 한 곳에 모은다.
 *
 * <ol>
 *   <li><b>양수</b> — 방향은 엔트리 유형이 정한다. 금액에 부호를 넣지 않는다.
 *   <li><b>1원 단위 정수</b> — 소수 포인트가 한 번이라도 유입되면 이후 적립 절사와
 *       소멸 정산이 전부 미세하게 어긋난다. DB {@code chk_*_integral} 은 이 규약의 최후 방어선이다.
 * </ol>
 *
 * <p>{@code 100.00} 처럼 스케일만 있고 값이 정수면 통과시킨다 — JDBC 가 NUMERIC(19,2) 를
 * 그런 형태로 돌려주기 때문이다. 거절 대상은 값 자체에 소수부가 있는 경우다.
 */
final class PointAmounts {

    /** 저장 스케일 — 컬럼이 NUMERIC(19,2) 라 맞춘다. */
    static final int SCALE = 2;

    private PointAmounts() {
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 양수 + 1원 단위 정수를 강제하고 저장 스케일로 정규화한다. */
    static BigDecimal requirePoint(BigDecimal amount, String operation) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPointAmountException(
                    operation + " 금액은 양수여야 합니다: " + amount, operation, amount);
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new InvalidPointAmountException(
                    operation + " 금액은 1원 단위 정수여야 합니다: " + amount, operation, amount);
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 이미 검증된 값(영속 복원 등)을 저장 스케일로만 맞춘다. */
    static BigDecimal normalize(BigDecimal value, String operation) {
        if (value == null) {
            throw new InvalidPointAmountException("금액은 null 일 수 없습니다", operation, null);
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
