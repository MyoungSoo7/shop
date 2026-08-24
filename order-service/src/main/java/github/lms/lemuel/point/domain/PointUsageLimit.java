package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointUsageLimitExceededException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 주문당 포인트 사용 상한.
 *
 * <p>지금까지 포인트는 잔액만 있으면 결제 전액을 덮을 수 있었다. 실무 커머스는 대개 상한을 둔다 —
 * 정액("주문당 최대 1 만 포인트")이거나 비율("결제액의 30% 까지")이다. 그 상한을 코드가 아니라
 * 정책 데이터로 두어야 판촉 기간마다 배포하지 않는다.
 *
 * <p><b>라운딩:</b> 비율 상한의 원 미만은 버린다({@link RoundingMode#DOWN}). 올리면 정책이 약속한
 * 30% 보다 조금 더 쓸 수 있게 되고, 그 "조금"이 수백만 건 쌓이면 판촉비가 정책과 어긋난다.
 *
 * <p><b>0 과 없음은 다르다:</b> 한도 0(정액)은 "포인트 사용 금지", {@link PointUsageLimitType#NONE} 은
 * "상한 없음"이다.
 */
public final class PointUsageLimit {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PointUsageLimitType type;
    private final BigDecimal limitAmount;
    private final BigDecimal limitRatioPercent;

    private PointUsageLimit(PointUsageLimitType type, BigDecimal limitAmount, BigDecimal limitRatioPercent) {
        this.type = type;
        this.limitAmount = limitAmount;
        this.limitRatioPercent = limitRatioPercent;
    }

    /** 상한 없음 — 정책 행이 없을 때의 착지값이기도 하다(기존 동작 보존). */
    public static PointUsageLimit none() {
        return new PointUsageLimit(PointUsageLimitType.NONE, null, null);
    }

    public static PointUsageLimit fixedAmount(BigDecimal limitAmount) {
        if (limitAmount == null || limitAmount.signum() < 0) {
            throw new InvalidPointStateException(
                    "정액 사용 한도는 0 이상이어야 합니다: " + limitAmount, "NONE", "usage-limit");
        }
        return new PointUsageLimit(PointUsageLimitType.FIXED_AMOUNT, limitAmount, null);
    }

    public static PointUsageLimit orderRatio(BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new InvalidPointStateException(
                    "비율 사용 한도는 0~100 이어야 합니다: " + percent, "NONE", "usage-limit");
        }
        return new PointUsageLimit(PointUsageLimitType.ORDER_RATIO, null, percent);
    }

    /** 영속 레코드 복원 — 검증은 저장 시점에 통과했으므로 그대로 재구성한다. */
    public static PointUsageLimit rehydrate(PointUsageLimitType type, BigDecimal limitAmount,
                                            BigDecimal limitRatioPercent) {
        return new PointUsageLimit(type == null ? PointUsageLimitType.NONE : type,
                limitAmount, limitRatioPercent);
    }

    /**
     * 이 주문에 쓸 수 있는 포인트 상한. 어떤 경우에도 주문금액을 넘지 않는다 —
     * 결제액보다 많은 포인트를 쓰는 주문은 존재할 수 없다.
     */
    public BigDecimal maxUsable(BigDecimal orderAmount) {
        if (orderAmount == null || orderAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return switch (type) {
            case NONE -> orderAmount;
            case FIXED_AMOUNT -> limitAmount.min(orderAmount);
            case ORDER_RATIO -> orderAmount.multiply(limitRatioPercent)
                    .divide(HUNDRED, 0, RoundingMode.DOWN)
                    .min(orderAmount);
        };
    }

    /** 요청 사용액이 상한 안인지 검사한다. 경계(정확히 상한)는 허용. */
    public void assertWithin(BigDecimal orderAmount, BigDecimal requested) {
        if (requested == null || requested.signum() <= 0) {
            return;
        }
        BigDecimal max = maxUsable(orderAmount);
        if (requested.compareTo(max) > 0) {
            throw new PointUsageLimitExceededException(requested, max);
        }
    }

    public PointUsageLimitType getType() { return type; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public BigDecimal getLimitRatioPercent() { return limitRatioPercent; }
}
