package github.lms.lemuel.sellertier.domain;

import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 등급 구간 판정 — 순수 함수.
 *
 * <p>임계 거래액은 수수료 수입과 직결돼 재무 승인 사항이라 <b>값이 아니라 구조</b>를 먼저 못박는다.
 * 임계는 설정으로 주입되고, 이 테스트는 "주어진 임계에서 구간 판정이 옳은가"만 본다.
 */
class SellerTierPolicyTest {

    /** VIP 5억 · STRATEGIC 30억 — 테스트용 임계(운영값은 설정에서 온다). */
    private SellerTierPolicy policy() {
        return SellerTierPolicy.of(new BigDecimal("500000000"), new BigDecimal("3000000000"));
    }

    private BigDecimal won(String v) {
        return new BigDecimal(v);
    }

    @Test @DisplayName("임계 미만은 NORMAL")
    void belowVipThreshold() {
        assertThat(policy().tierFor(won("499999999"))).isEqualTo(SellerTierGrade.NORMAL);
    }

    @Test @DisplayName("VIP 임계 정확히 도달하면 VIP — 경계는 포함")
    void exactlyAtVipThreshold() {
        assertThat(policy().tierFor(won("500000000"))).isEqualTo(SellerTierGrade.VIP);
    }

    @Test @DisplayName("STRATEGIC 임계 정확히 도달하면 STRATEGIC")
    void exactlyAtStrategicThreshold() {
        assertThat(policy().tierFor(won("3000000000"))).isEqualTo(SellerTierGrade.STRATEGIC);
    }

    @Test @DisplayName("거래액이 없거나 0 이면 NORMAL — 신규 셀러가 상위 등급으로 시작하지 않는다")
    void zeroOrNullIsNormal() {
        assertThat(policy().tierFor(BigDecimal.ZERO)).isEqualTo(SellerTierGrade.NORMAL);
        assertThat(policy().tierFor(null)).isEqualTo(SellerTierGrade.NORMAL);
    }

    @Test @DisplayName("음수 거래액(환불 초과)도 NORMAL 로 떨어진다")
    void negativeIsNormal() {
        assertThat(policy().tierFor(won("-1000"))).isEqualTo(SellerTierGrade.NORMAL);
    }

    @Test @DisplayName("STRATEGIC 임계는 VIP 임계보다 커야 한다 — 뒤집히면 구간이 성립하지 않는다")
    void thresholdsMustBeOrdered() {
        assertThatThrownBy(() -> SellerTierPolicy.of(won("3000000000"), won("500000000")))
                .isInstanceOf(SellerTierPolicyException.class);
    }

    @Test @DisplayName("임계는 양수여야 한다 — 0 이면 전원이 그 등급으로 승급한다")
    void thresholdsMustBePositive() {
        assertThatThrownBy(() -> SellerTierPolicy.of(BigDecimal.ZERO, won("3000000000")))
                .isInstanceOf(SellerTierPolicyException.class);
        assertThatThrownBy(() -> SellerTierPolicy.of(won("500000000"), won("-1")))
                .isInstanceOf(SellerTierPolicyException.class);
    }

    @Test @DisplayName("임계가 없으면 정책을 만들 수 없다 — 미승인 상태로 자동 승급이 도는 것을 막는다")
    void thresholdsAreRequired() {
        assertThatThrownBy(() -> SellerTierPolicy.of(null, won("3000000000")))
                .isInstanceOf(SellerTierPolicyException.class);
    }
}
