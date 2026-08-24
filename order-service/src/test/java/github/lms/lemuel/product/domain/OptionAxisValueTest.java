package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptionAxisValue — 축의 표준 값")
class OptionAxisValueTest {

    private static final Long AXIS_ID = 1L;

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("표시색 없이 만들 수 있다")
        void createsWithoutSwatch() {
            OptionAxisValue value = OptionAxisValue.create(AXIS_ID, "L", "L", null, 1);

            assertThat(value.getAxisId()).isEqualTo(AXIS_ID);
            assertThat(value.getCode()).isEqualTo("L");
            assertThat(value.getSwatchHex()).isNull();
            assertThat(value.getSortOrder()).isEqualTo(1);
            assertThat(value.isActive()).isTrue();
        }

        @Test
        @DisplayName("표시색은 #RRGGBB 로 저장한다")
        void createsWithSwatch() {
            assertThat(OptionAxisValue.create(AXIS_ID, "RED", "빨강", "#FF0000", 0).getSwatchHex())
                    .isEqualTo("#FF0000");
        }

        @Test
        @DisplayName("빈 문자열 표시색은 null 로 정규화한다")
        void normalizesBlankSwatchToNull() {
            assertThat(OptionAxisValue.create(AXIS_ID, "RED", "빨강", "   ", 0).getSwatchHex())
                    .isNull();
        }

        @ParameterizedTest(name = "[{index}] 잘못된 표시색: \"{0}\"")
        @ValueSource(strings = {"FF0000", "#FFF", "#GG0000", "#FF00000", "red"})
        @DisplayName("#RRGGBB 가 아닌 표시색은 거부한다")
        void rejectsInvalidSwatch(String swatchHex) {
            assertThatThrownBy(() -> OptionAxisValue.create(AXIS_ID, "RED", "빨강", swatchHex, 0))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("#RRGGBB");
        }

        @Test
        @DisplayName("소문자 표시색도 허용한다")
        void allowsLowercaseSwatch() {
            assertThat(OptionAxisValue.create(AXIS_ID, "RED", "빨강", "#ff0000", 0).getSwatchHex())
                    .isEqualTo("#ff0000");
        }

        @Test
        @DisplayName("숫자로 시작하는 코드를 허용한다 — '2XL' 같은 값")
        void allowsDigitLeadingCode() {
            assertThat(OptionAxisValue.create(AXIS_ID, "2XL", "2XL", null, 0).getCode())
                    .isEqualTo("2XL");
        }

        @ParameterizedTest(name = "[{index}] 잘못된 코드: \"{0}\"")
        @ValueSource(strings = {"", "  ", "빨 강", "빨강:", "빨강/파랑"})
        @DisplayName("공백·구분자가 든 코드는 거부한다")
        void rejectsInvalidCode(String code) {
            assertThatThrownBy(() -> OptionAxisValue.create(AXIS_ID, code, "빨강", null, 0))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("axisId null 은 거부한다")
        void rejectsNullAxisId() {
            assertThatThrownBy(() -> OptionAxisValue.create(null, "RED", "빨강", null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("이름이 비면 거부한다")
        void rejectsBlankName() {
            assertThatThrownBy(() -> OptionAxisValue.create(AXIS_ID, "RED", " ", null, 0))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("이름 101자는 거부한다 (경계)")
        void rejectsTooLongName() {
            assertThatThrownBy(() -> OptionAxisValue.create(AXIS_ID, "RED", "가".repeat(101), null, 0))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("정렬 순서 0 은 허용, -1 은 거부한다 (경계)")
        void enforcesSortOrderBoundary() {
            assertThat(OptionAxisValue.create(AXIS_ID, "RED", "빨강", null, 0).getSortOrder())
                    .isZero();

            assertThatThrownBy(() -> OptionAxisValue.create(AXIS_ID, "RED", "빨강", null, -1))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("0 이상");
        }
    }

    @Nested
    @DisplayName("변경")
    class Mutation {

        private OptionAxisValue value() {
            return OptionAxisValue.create(AXIS_ID, "RED", "빨강", "#FF0000", 0);
        }

        @Test
        @DisplayName("이름을 바꿔도 코드는 불변이다")
        void renameKeepsCode() {
            OptionAxisValue value = value();

            value.rename("레드");

            assertThat(value.getName()).isEqualTo("레드");
            assertThat(value.getCode()).isEqualTo("RED");
        }

        @Test
        @DisplayName("표시색을 null 로 지울 수 있다")
        void clearsSwatch() {
            OptionAxisValue value = value();

            value.changeSwatchHex(null);

            assertThat(value.getSwatchHex()).isNull();
        }

        @Test
        @DisplayName("잘못된 표시색으로는 바꿀 수 없다")
        void rejectsInvalidSwatchChange() {
            OptionAxisValue value = value();

            assertThatThrownBy(() -> value.changeSwatchHex("#XYZ"))
                    .isInstanceOf(ProductInvariantViolationException.class);
            assertThat(value.getSwatchHex()).isEqualTo("#FF0000");
        }

        @Test
        @DisplayName("정렬 순서를 바꿀 수 있고 음수는 거부한다")
        void changesSortOrder() {
            OptionAxisValue value = value();

            value.changeSortOrder(5);
            assertThat(value.getSortOrder()).isEqualTo(5);

            assertThatThrownBy(() -> value.changeSortOrder(-1))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("비활성화·활성화한다")
        void togglesActive() {
            OptionAxisValue value = value();

            value.deactivate();
            assertThat(value.isActive()).isFalse();

            value.activate();
            assertThat(value.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("축 제약 충족 판정")
    class Satisfies {

        private final OptionAxis swatchAxis =
                OptionAxis.rehydrate(AXIS_ID, "COLOR", "색상", OptionInputType.SWATCH, true);
        private final OptionAxis selectAxis =
                OptionAxis.rehydrate(AXIS_ID, "SIZE", "사이즈", OptionInputType.SELECT, true);

        @Test
        @DisplayName("SWATCH 축인데 표시색이 없으면 충족하지 못한다")
        void swatchAxisRequiresSwatchHex() {
            OptionAxisValue noSwatch = OptionAxisValue.create(AXIS_ID, "RED", "빨강", null, 0);

            assertThat(noSwatch.satisfies(swatchAxis)).isFalse();
        }

        @Test
        @DisplayName("SWATCH 축에 표시색이 있으면 충족한다")
        void swatchAxisWithSwatchHex() {
            OptionAxisValue withSwatch = OptionAxisValue.create(AXIS_ID, "RED", "빨강", "#FF0000", 0);

            assertThat(withSwatch.satisfies(swatchAxis)).isTrue();
        }

        @Test
        @DisplayName("SELECT 축은 표시색 없이도 충족한다")
        void selectAxisNeedsNoSwatch() {
            OptionAxisValue noSwatch = OptionAxisValue.create(AXIS_ID, "L", "L", null, 0);

            assertThat(noSwatch.satisfies(selectAxis)).isTrue();
        }

        @Test
        @DisplayName("다른 축으로 검사하면 거부한다")
        void rejectsForeignAxis() {
            OptionAxisValue value = OptionAxisValue.create(AXIS_ID, "L", "L", null, 0);
            OptionAxis other = OptionAxis.rehydrate(99L, "OTHER", "다른축", OptionInputType.SELECT, true);

            assertThatThrownBy(() -> value.satisfies(other))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("다른 축");
        }

        @Test
        @DisplayName("축 null 은 거부한다")
        void rejectsNullAxis() {
            OptionAxisValue value = OptionAxisValue.create(AXIS_ID, "L", "L", null, 0);

            assertThatThrownBy(() -> value.satisfies(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("식별자")
    class Identity {

        @Test
        @DisplayName("rehydrate 는 저장된 상태를 복원한다")
        void rehydrates() {
            OptionAxisValue value =
                    OptionAxisValue.rehydrate(3L, AXIS_ID, "RED", "빨강", "#FF0000", 2, false);

            assertThat(value.getId()).isEqualTo(3L);
            assertThat(value.isActive()).isFalse();
            assertThat(value.getSortOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("id 는 1 회만 부여할 수 있다")
        void assignIdOnce() {
            OptionAxisValue value = OptionAxisValue.create(AXIS_ID, "RED", "빨강", null, 0);

            value.assignId(10L);

            assertThatThrownBy(() -> value.assignId(11L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
