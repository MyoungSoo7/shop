package github.lms.lemuel.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("생성 시 scale 2, HALF_UP 로 정규화된다")
    void normalizesScaleOnCreation() {
        assertThat(Money.of("1750").toBigDecimal()).isEqualTo(new BigDecimal("1750.00"));
        assertThat(Money.of("1.005").toBigDecimal()).isEqualTo(new BigDecimal("1.01")); // HALF_UP
        assertThat(Money.won(50000).toBigDecimal()).isEqualTo(new BigDecimal("50000.00"));
        assertThat(Money.of(new BigDecimal("2.344")).toBigDecimal()).isEqualTo(new BigDecimal("2.34")); // down
        assertThat(Money.SCALE).isEqualTo(2);
        assertThat(Money.ZERO.toBigDecimal()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("record 접근자 amount() 는 정규화된 값을 노출한다")
    void accessorReturnsNormalized() {
        assertThat(Money.of("100").amount()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("값 기반 동등성 — scale 가 달라도 같은 값이면 동등")
    void valueEquality() {
        assertThat(Money.of("100")).isEqualTo(Money.of("100.00"));
        assertThat(Money.of(new BigDecimal("100.000"))).isEqualTo(Money.won(100));
        assertThat(Money.ZERO).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("덧셈/뺄셈/곱셈")
    void arithmetic() {
        assertThat(Money.won(50000).minus(Money.of("1750.00")))
                .isEqualTo(Money.of("48250.00"));
        assertThat(Money.of("10000.00").plus(Money.of("300.00")))
                .isEqualTo(Money.of("10300.00"));
        // 수수료율 3.5% → 50000 * 0.035 = 1750.00
        assertThat(Money.won(50000).times(new BigDecimal("0.035")))
                .isEqualTo(Money.of("1750.00"));
    }

    @Test
    @DisplayName("곱셈 결과는 scale 2 HALF_UP 로 반올림 — 기존 도메인 계산과 일치")
    void multiplyRounds() {
        // 33333 * 0.035 = 1166.655 → 1166.66
        assertThat(Money.won(33333).times(new BigDecimal("0.035")))
                .isEqualTo(Money.of("1166.66"));
    }

    @Test
    @DisplayName("negate — 부호 반전")
    void negate() {
        assertThat(Money.won(100).negate()).isEqualTo(Money.of("-100.00"));
        assertThat(Money.of("-42.50").negate()).isEqualTo(Money.of("42.50"));
        assertThat(Money.ZERO.negate()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("min/max")
    void minMax() {
        assertThat(Money.won(1000).min(Money.won(300))).isEqualTo(Money.won(300));
        assertThat(Money.won(1000).max(Money.won(300))).isEqualTo(Money.won(1000));
        assertThat(Money.of("-50.00").max(Money.ZERO)).isEqualTo(Money.ZERO);
        // 동률일 때 min/max 는 수신자(this)를 반환
        assertThat(Money.won(5).min(Money.won(5))).isEqualTo(Money.won(5));
        assertThat(Money.won(5).max(Money.won(5))).isEqualTo(Money.won(5));
    }

    @Test
    @DisplayName("부호/비교 판별")
    void predicates() {
        assertThat(Money.of("-1.00").isNegative()).isTrue();
        assertThat(Money.won(1).isNegative()).isFalse();
        assertThat(Money.ZERO.isZeroOrNegative()).isTrue();
        assertThat(Money.of("-1.00").isZeroOrNegative()).isTrue();
        assertThat(Money.won(1).isZeroOrNegative()).isFalse();
        assertThat(Money.won(1).isPositive()).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.won(1).isZero()).isFalse();
        assertThat(Money.won(100).isGreaterThan(Money.won(99))).isTrue();
        assertThat(Money.won(99).isGreaterThan(Money.won(100))).isFalse();
        assertThat(Money.won(100).isLessThanOrEqualTo(Money.won(100))).isTrue();
        assertThat(Money.won(101).isLessThanOrEqualTo(Money.won(100))).isFalse();
    }

    @Test
    @DisplayName("null amount/multiplier 는 거부")
    void rejectsNull() {
        assertThatThrownBy(() -> Money.of((BigDecimal) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.won(100).times(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
