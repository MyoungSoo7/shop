package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적립 단위·라운딩 정책.
 *
 * <p>실무 커머스는 "10 원 단위 적립", "100 원 단위 절사" 같은 단위 정책을 갖는다. 적립률만 데이터로
 * 두고 라운딩을 코드 상수로 박아 두면, 단위를 바꾸는 데 배포가 필요하고 그 사이 판촉비가 정책과
 * 어긋난다. 단위와 방식을 정책 행에 함께 둔다.
 *
 * <p>기존 행은 단위 1 · 버림으로 복원되어 이전과 같은 금액이 나온다(하위 호환).
 */
@DisplayName("PointEarnPolicy — 적립 단위·라운딩")
class PointEarnRoundingTest {

    private static PointEarnPolicy policy(String rate, int unit, PointEarnRounding rounding) {
        return PointEarnPolicy.of(PointEarnScope.GLOBAL, "ALL", new BigDecimal(rate), 365,
                LocalDate.of(2026, 1, 1), null, "테스트", "tester", unit, rounding);
    }

    @Test
    @DisplayName("단위 1 · 버림은 기존 동작과 같다 — 원 미만 절사")
    void unitOneDownMatchesLegacy() {
        PointEarnPolicy p = policy("0.015", 1, PointEarnRounding.DOWN);

        assertThat(p.earnFor(new BigDecimal("12345"))).isEqualByComparingTo("185"); // 185.175 → 185
    }

    @Test
    @DisplayName("10 원 단위 버림 — 185.175 → 180")
    void tenUnitDown() {
        assertThat(policy("0.015", 10, PointEarnRounding.DOWN).earnFor(new BigDecimal("12345")))
                .isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("10 원 단위 반올림 — 185.175 → 190")
    void tenUnitHalfUp() {
        assertThat(policy("0.015", 10, PointEarnRounding.HALF_UP).earnFor(new BigDecimal("12345")))
                .isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("10 원 단위 올림 — 185.175 → 190")
    void tenUnitUp() {
        assertThat(policy("0.015", 10, PointEarnRounding.UP).earnFor(new BigDecimal("12345")))
                .isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("100 원 단위 반올림 경계 — 정확히 절반은 올린다")
    void hundredUnitHalfBoundary() {
        // 10,000 × 0.015 = 150 → 100 단위 반올림 → 200
        assertThat(policy("0.015", 100, PointEarnRounding.HALF_UP).earnFor(new BigDecimal("10000")))
                .isEqualByComparingTo("200");
        assertThat(policy("0.015", 100, PointEarnRounding.DOWN).earnFor(new BigDecimal("10000")))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("올림이어도 적립액이 0 이면 0 — 주문 금액이 0 이하면 적립 없음")
    void zeroOrderNoEarnEvenWithCeiling() {
        assertThat(policy("0.015", 100, PointEarnRounding.UP).earnFor(BigDecimal.ZERO))
                .isEqualByComparingTo("0");
        assertThat(policy("0.015", 100, PointEarnRounding.UP).earnFor(null))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("올림 단위 정책은 1 원짜리 주문에도 한 단위를 지급한다")
    void ceilingGrantsOneUnitForTinyOrder() {
        assertThat(policy("0.015", 10, PointEarnRounding.UP).earnFor(BigDecimal.ONE))
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("적립 단위는 양수여야 한다")
    void unitMustBePositive() {
        assertThatThrownBy(() -> policy("0.015", 0, PointEarnRounding.DOWN))
                .isInstanceOf(InvalidPointStateException.class);
        assertThatThrownBy(() -> policy("0.015", -10, PointEarnRounding.DOWN))
                .isInstanceOf(InvalidPointStateException.class);
    }

    @Test
    @DisplayName("라운딩 방식은 필수")
    void roundingRequired() {
        assertThatThrownBy(() -> policy("0.015", 10, null))
                .isInstanceOf(InvalidPointStateException.class);
    }

    @Test
    @DisplayName("단위·방식을 생략한 기존 팩토리는 1 원 단위 버림으로 착지한다")
    void legacyFactoryDefaults() {
        PointEarnPolicy p = PointEarnPolicy.of(PointEarnScope.GLOBAL, "ALL", new BigDecimal("0.015"),
                365, LocalDate.of(2026, 1, 1), null, "테스트", "tester");

        assertThat(p.getRoundingUnit()).isEqualTo(1);
        assertThat(p.getRounding()).isEqualTo(PointEarnRounding.DOWN);
        assertThat(p.earnFor(new BigDecimal("12345"))).isEqualByComparingTo("185");
    }

    @Test
    @DisplayName("복원 팩토리도 단위·방식을 보존한다 — 재기동 후 적립액이 달라지지 않는다")
    void rehydratePreservesRounding() {
        PointEarnPolicy p = PointEarnPolicy.rehydrate(3L, PointEarnScope.GLOBAL, "ALL",
                new BigDecimal("0.015"), 365, LocalDate.of(2026, 1, 1), null, "r", "t",
                100, PointEarnRounding.HALF_UP);

        assertThat(p.earnFor(new BigDecimal("12345"))).isEqualByComparingTo("200");
    }
}
