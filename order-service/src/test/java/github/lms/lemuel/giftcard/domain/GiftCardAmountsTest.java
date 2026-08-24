package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GiftCardAmounts 단위 테스트")
class GiftCardAmountsTest {

    @Nested
    @DisplayName("zero — 0원 기본값")
    class ZeroTests {

        @Test
        @DisplayName("zero()의 반환값은 0이고 스케일은 2여야 한다")
        void zero_returnsZeroWithScaleTwo() {
            BigDecimal zero = GiftCardAmounts.zero();

            assertThat(zero).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(zero.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("require — 기프트카드 금액 검증 및 정규화")
    class RequireTests {

        @Test
        @DisplayName("null 입력 시 InvalidGiftCardAmountException 예외가 발생한다")
        void require_null_throwsException() {
            assertThatThrownBy(() -> GiftCardAmounts.require(null, "TEST_OP"))
                    .isInstanceOf(InvalidGiftCardAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 양수여야 합니다: null");
        }

        @Test
        @DisplayName("0 입력 시 InvalidGiftCardAmountException 예외가 발생한다")
        void require_zero_throwsException() {
            assertThatThrownBy(() -> GiftCardAmounts.require(BigDecimal.ZERO, "TEST_OP"))
                    .isInstanceOf(InvalidGiftCardAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 양수여야 합니다: 0");
        }

        @Test
        @DisplayName("음수 입력 시 InvalidGiftCardAmountException 예외가 발생한다")
        void require_negative_throwsException() {
            BigDecimal negative = new BigDecimal("-10");
            assertThatThrownBy(() -> GiftCardAmounts.require(negative, "TEST_OP"))
                    .isInstanceOf(InvalidGiftCardAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 양수여야 합니다: -10");
        }

        @Test
        @DisplayName("소수부가 있는 값(예: 0.5) 입력 시 InvalidGiftCardAmountException 예외가 발생한다")
        void require_withDecimal_throwsException() {
            BigDecimal decimalValue = new BigDecimal("0.5");
            assertThatThrownBy(() -> GiftCardAmounts.require(decimalValue, "TEST_OP"))
                    .isInstanceOf(InvalidGiftCardAmountException.class)
                    .hasMessageContaining("TEST_OP 금액은 1원 단위 정수여야 합니다: 0.5");
        }

        @Test
        @DisplayName("스케일만 있는 정수(예: 100.00) 입력 시 정상 통과하고 반환값의 스케일은 2여야 한다")
        void require_scaleOnlyInteger_success() {
            BigDecimal amount = new BigDecimal("100.00");
            BigDecimal result = GiftCardAmounts.require(amount, "TEST_OP");

            assertThat(result).isEqualByComparingTo(amount);
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("일반 양의 정수(예: 100) 입력 시 정상 통과하고 반환값의 스케일은 2여야 한다")
        void require_positiveInteger_success() {
            BigDecimal amount = new BigDecimal("100");
            BigDecimal result = GiftCardAmounts.require(amount, "TEST_OP");

            assertThat(result).isEqualByComparingTo(amount);
            assertThat(result.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("normalize — 기저장 데이터 정규화")
    class NormalizeTests {

        @Test
        @DisplayName("null 입력 시 InvalidGiftCardAmountException 예외가 발생한다")
        void normalize_null_throwsException() {
            assertThatThrownBy(() -> GiftCardAmounts.normalize(null, "TEST_OP"))
                    .isInstanceOf(InvalidGiftCardAmountException.class)
                    .hasMessageContaining("금액은 null 일 수 없습니다");
        }

        @Test
        @DisplayName("HALF_UP 반올림이 올바르게 적용되어 반환되고 반환값의 스케일은 2여야 한다")
        void normalize_halfUpRounding_returnsScaleTwo() {
            // 100.124 -> 100.12 (내림)
            BigDecimal roundedDown = GiftCardAmounts.normalize(new BigDecimal("100.124"), "TEST_OP");
            assertThat(roundedDown).isEqualTo(new BigDecimal("100.12"));
            assertThat(roundedDown.scale()).isEqualTo(2);

            // 100.125 -> 100.13 (올림)
            BigDecimal roundedUp = GiftCardAmounts.normalize(new BigDecimal("100.125"), "TEST_OP");
            assertThat(roundedUp).isEqualTo(new BigDecimal("100.13"));
            assertThat(roundedUp.scale()).isEqualTo(2);

            // 100.126 -> 100.13 (올림)
            BigDecimal roundedUp6 = GiftCardAmounts.normalize(new BigDecimal("100.126"), "TEST_OP");
            assertThat(roundedUp6).isEqualTo(new BigDecimal("100.13"));
            assertThat(roundedUp6.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("음수도 저장 스케일로만 맞춰 그대로 통과시킨다 — 부호 판정은 require 의 몫이다")
        void normalize_negative_keepsSignAndScale() {
            BigDecimal normalized = GiftCardAmounts.normalize(new BigDecimal("-100.125"), "TEST_OP");

            assertThat(normalized).isEqualTo(new BigDecimal("-100.13"));
            assertThat(normalized.scale()).isEqualTo(2);
        }
    }
}
