package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PointAmounts 단위 테스트")
class PointAmountsTest {

    @Nested
    @DisplayName("zero — 0원 기본값")
    class ZeroTests {

        @Test
        @DisplayName("zero()의 반환값은 0이고 스케일은 2여야 한다")
        void zero_returnsZeroWithScaleTwo() {
            BigDecimal zero = PointAmounts.zero();

            assertThat(zero).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(zero.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("requirePoint — 포인트 금액 검증 및 정규화")
    class RequirePointTests {

        @Test
        @DisplayName("null 입력 시 InvalidPointAmountException 예외가 발생한다")
        void requirePoint_null_throwsException() {
            assertThatThrownBy(() -> PointAmounts.requirePoint(null, "TEST_OP"))
                    .isInstanceOf(InvalidPointAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 양수여야 합니다: null");
        }

        @Test
        @DisplayName("0 입력 시 InvalidPointAmountException 예외가 발생한다")
        void requirePoint_zero_throwsException() {
            assertThatThrownBy(() -> PointAmounts.requirePoint(BigDecimal.ZERO, "TEST_OP"))
                    .isInstanceOf(InvalidPointAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 양수여야 합니다: 0");
        }

        @Test
        @DisplayName("음수 입력 시 InvalidPointAmountException 예외가 발생한다")
        void requirePoint_negative_throwsException() {
            BigDecimal negative = new BigDecimal("-10");
            assertThatThrownBy(() -> PointAmounts.requirePoint(negative, "TEST_OP"))
                    .isInstanceOf(InvalidPointAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 양수여야 합니다: -10");
        }

        @Test
        @DisplayName("소수부가 있는 값(예: 0.5) 입력 시 InvalidPointAmountException 예외가 발생한다")
        void requirePoint_withDecimal_throwsException() {
            BigDecimal decimalValue = new BigDecimal("0.5");
            assertThatThrownBy(() -> PointAmounts.requirePoint(decimalValue, "TEST_OP"))
                    .isInstanceOf(InvalidPointAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 1원 단위 정수여야 합니다: 0.5");
        }

        @Test
        @DisplayName("스케일만 있는 정수(예: 100.00) 입력 시 정상 통과하고 반환값의 스케일은 2여야 한다")
        void requirePoint_scaleOnlyInteger_success() {
            BigDecimal amount = new BigDecimal("100.00");
            BigDecimal result = PointAmounts.requirePoint(amount, "TEST_OP");

            assertThat(result).isEqualByComparingTo(amount);
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("일반 양의 정수(예: 100) 입력 시 정상 통과하고 반환값의 스케일은 2여야 한다")
        void requirePoint_positiveInteger_success() {
            BigDecimal amount = new BigDecimal("100");
            BigDecimal result = PointAmounts.requirePoint(amount, "TEST_OP");

            assertThat(result).isEqualByComparingTo(amount);
            assertThat(result.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("normalize — 기저장 데이터 정규화")
    class NormalizeTests {

        @Test
        @DisplayName("null 입력 시 InvalidPointAmountException 예외가 발생한다")
        void normalize_null_throwsException() {
            assertThatThrownBy(() -> PointAmounts.normalize(null, "TEST_OP"))
                    .isInstanceOf(InvalidPointAmountException.class)
                    .hasMessageContaining("금액은 null 일 수 없습니다");
        }

        @Test
        @DisplayName("HALF_UP 반올림이 올바르게 적용되어 반환되고 반환값의 스케일은 2여야 한다")
        void normalize_halfUpRounding_returnsScaleTwo() {
            // 100.124 -> 100.12 (내림)
            BigDecimal roundedDown = PointAmounts.normalize(new BigDecimal("100.124"), "TEST_OP");
            assertThat(roundedDown).isEqualTo(new BigDecimal("100.12"));
            assertThat(roundedDown.scale()).isEqualTo(2);

            // 100.125 -> 100.13 (올림)
            BigDecimal roundedUp = PointAmounts.normalize(new BigDecimal("100.125"), "TEST_OP");
            assertThat(roundedUp).isEqualTo(new BigDecimal("100.13"));
            assertThat(roundedUp.scale()).isEqualTo(2);

            // 100.126 -> 100.13 (올림)
            BigDecimal roundedUp6 = PointAmounts.normalize(new BigDecimal("100.126"), "TEST_OP");
            assertThat(roundedUp6).isEqualTo(new BigDecimal("100.13"));
            assertThat(roundedUp6.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("음수도 저장 스케일로만 맞춰 그대로 통과시킨다 — 부호 판정은 requirePoint 의 몫이다")
        void normalize_negative_keepsSignAndScale() {
            BigDecimal normalized = PointAmounts.normalize(new BigDecimal("-100.125"), "TEST_OP");

            assertThat(normalized).isEqualTo(new BigDecimal("-100.13"));
            assertThat(normalized.scale()).isEqualTo(2);
        }
    }
}
