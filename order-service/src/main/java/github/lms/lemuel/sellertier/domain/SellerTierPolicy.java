package github.lms.lemuel.sellertier.domain;

import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;

import java.math.BigDecimal;

/**
 * 등급 구간표 — 12개월 결제 순액을 등급으로 옮긴다 (ADR 0031).
 *
 * <p><b>임계는 코드가 아니라 설정에서 온다.</b> 등급 승급은 곧 수수료 인하라(NORMAL 3.5% → VIP 2.5%)
 * 임계값 결정은 재무 판단이다. 그 값을 코드에 박으면 승인 때마다 배포가 필요해지고, 무엇보다 승인 전
 * 임의의 값으로 자동 승급이 도는 사고가 난다. 생성 시점에 임계를 검증해 성립하지 않는 정책은 아예
 * 만들어지지 않게 한다.
 *
 * <p>판정은 DB·시계에 접근하지 않는 순수 함수다 — 레거시 사례가 이 판정을 80줄 UNION 중첩 SQL 에
 * 넣어 테스트가 불가능했던 것의 반면교사.
 */
public final class SellerTierPolicy {

    private final BigDecimal vipThreshold;
    private final BigDecimal strategicThreshold;

    private SellerTierPolicy(BigDecimal vipThreshold, BigDecimal strategicThreshold) {
        this.vipThreshold = vipThreshold;
        this.strategicThreshold = strategicThreshold;
    }

    public static SellerTierPolicy of(BigDecimal vipThreshold, BigDecimal strategicThreshold) {
        requirePositive(vipThreshold, "VIP");
        requirePositive(strategicThreshold, "STRATEGIC");
        if (strategicThreshold.compareTo(vipThreshold) <= 0) {
            throw new SellerTierPolicyException(
                    "STRATEGIC 임계는 VIP 임계보다 커야 합니다: VIP=" + vipThreshold
                            + ", STRATEGIC=" + strategicThreshold);
        }
        return new SellerTierPolicy(vipThreshold, strategicThreshold);
    }

    /** 12개월 결제 순액(CAPTURED − 환불)에 해당하는 등급. 미상·0·음수는 NORMAL. */
    public SellerTierGrade tierFor(BigDecimal net12m) {
        if (net12m == null || net12m.signum() <= 0) {
            return SellerTierGrade.NORMAL;
        }
        if (net12m.compareTo(strategicThreshold) >= 0) {
            return SellerTierGrade.STRATEGIC;
        }
        return net12m.compareTo(vipThreshold) >= 0 ? SellerTierGrade.VIP : SellerTierGrade.NORMAL;
    }

    public BigDecimal vipThreshold() { return vipThreshold; }
    public BigDecimal strategicThreshold() { return strategicThreshold; }

    private static void requirePositive(BigDecimal threshold, String label) {
        if (threshold == null) {
            throw new SellerTierPolicyException(label + " 임계가 설정되지 않았습니다"
                    + " — 임계 미승인 상태로 자동 승급이 돌면 안 됩니다");
        }
        if (threshold.signum() <= 0) {
            throw new SellerTierPolicyException(
                    label + " 임계는 양수여야 합니다(0 이면 전원이 그 등급으로 승급): " + threshold);
        }
    }
}
