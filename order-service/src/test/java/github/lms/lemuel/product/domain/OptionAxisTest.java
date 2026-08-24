package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptionAxis — 표준 옵션 축")
class OptionAxisTest {

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("코드·이름·입력방식으로 활성 상태의 축을 만든다")
        void createsActiveAxis() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SWATCH);

            assertThat(axis.getId()).isNull();
            assertThat(axis.getCode()).isEqualTo("COLOR");
            assertThat(axis.getName()).isEqualTo("색상");
            assertThat(axis.getInputType()).isEqualTo(OptionInputType.SWATCH);
            assertThat(axis.isActive()).isTrue();
        }

        @Test
        @DisplayName("이름 앞뒤 공백은 제거한다")
        void trimsName() {
            assertThat(OptionAxis.create("SIZE", "  사이즈  ", OptionInputType.SELECT).getName())
                    .isEqualTo("사이즈");
        }

        @Test
        @DisplayName("한글 코드도 허용한다 — 레거시 표시명 백필이 축 이름을 코드로 쓴다")
        void allowsUnicodeCode() {
            assertThat(OptionAxis.create("색상", "색상", OptionInputType.SELECT).getCode())
                    .isEqualTo("색상");
        }

        @ParameterizedTest(name = "[{index}] 잘못된 코드: \"{0}\"")
        @ValueSource(strings = {"", "   ", "색 상", "색상:", "색상/사이즈", "_LEADING", "-LEADING"})
        @DisplayName("공백·구분자(':' '/')·기호 시작 코드는 거부한다")
        void rejectsInvalidCode(String code) {
            assertThatThrownBy(() -> OptionAxis.create(code, "색상", OptionInputType.SELECT))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("코드 null 은 거부한다")
        void rejectsNullCode() {
            assertThatThrownBy(() -> OptionAxis.create(null, "색상", OptionInputType.SELECT))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("필수");
        }

        @Test
        @DisplayName("코드 50자는 허용, 51자는 거부한다 (경계)")
        void enforcesCodeLengthBoundary() {
            String fifty = "A".repeat(50);
            assertThat(OptionAxis.create(fifty, "축", OptionInputType.SELECT).getCode()).hasSize(50);

            assertThatThrownBy(() -> OptionAxis.create("A".repeat(51), "축", OptionInputType.SELECT))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("이름 100자는 허용, 101자는 거부한다 (경계)")
        void enforcesNameLengthBoundary() {
            assertThat(OptionAxis.create("A", "가".repeat(100), OptionInputType.SELECT).getName())
                    .hasSize(100);

            assertThatThrownBy(() -> OptionAxis.create("A", "가".repeat(101), OptionInputType.SELECT))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("이름이 비면 거부한다")
        void rejectsBlankName() {
            assertThatThrownBy(() -> OptionAxis.create("COLOR", "  ", OptionInputType.SELECT))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("입력방식 null 은 거부한다")
        void rejectsNullInputType() {
            assertThatThrownBy(() -> OptionAxis.create("COLOR", "색상", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("변경")
    class Mutation {

        @Test
        @DisplayName("이름을 바꿔도 코드는 불변이다 — SKU·매핑이 흔들리지 않는 근거")
        void renameKeepsCode() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SELECT);

            axis.rename("컬러");

            assertThat(axis.getName()).isEqualTo("컬러");
            assertThat(axis.getCode()).isEqualTo("COLOR");
        }

        @Test
        @DisplayName("빈 이름으로는 바꿀 수 없다")
        void rejectsBlankRename() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SELECT);

            assertThatThrownBy(() -> axis.rename(""))
                    .isInstanceOf(ProductInvariantViolationException.class);
            assertThat(axis.getName()).isEqualTo("색상");
        }

        @Test
        @DisplayName("입력방식을 바꾸면 표시색 요구도 함께 바뀐다")
        void changeInputTypeAffectsSwatchRequirement() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SELECT);
            assertThat(axis.requiresSwatch()).isFalse();

            axis.changeInputType(OptionInputType.SWATCH);

            assertThat(axis.requiresSwatch()).isTrue();
        }

        @Test
        @DisplayName("입력방식 null 로는 바꿀 수 없다")
        void rejectsNullInputTypeChange() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SELECT);

            assertThatThrownBy(() -> axis.changeInputType(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("비활성화 후 다시 활성화할 수 있다")
        void deactivateAndActivate() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SELECT);

            axis.deactivate();
            assertThat(axis.isActive()).isFalse();

            axis.activate();
            assertThat(axis.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("식별자")
    class Identity {

        @Test
        @DisplayName("rehydrate 는 저장된 상태를 그대로 복원한다")
        void rehydrateRestoresState() {
            OptionAxis axis = OptionAxis.rehydrate(7L, "SIZE", "사이즈", OptionInputType.SELECT, false);

            assertThat(axis.getId()).isEqualTo(7L);
            assertThat(axis.isActive()).isFalse();
        }

        @Test
        @DisplayName("id 는 1 회만 부여할 수 있다")
        void assignIdOnce() {
            OptionAxis axis = OptionAxis.create("COLOR", "색상", OptionInputType.SELECT);

            axis.assignId(1L);
            assertThat(axis.getId()).isEqualTo(1L);

            assertThatThrownBy(() -> axis.assignId(2L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("입력방식")
    class InputTypes {

        @Test
        @DisplayName("SWATCH 만 표시색을 요구한다")
        void onlySwatchRequiresSwatch() {
            assertThat(OptionInputType.SWATCH.requiresSwatch()).isTrue();
            assertThat(OptionInputType.SELECT.requiresSwatch()).isFalse();
            assertThat(OptionInputType.TEXT.requiresSwatch()).isFalse();
        }

        @Test
        @DisplayName("TEXT 만 표준값 목록을 갖지 않는다")
        void onlyTextHasNoEnumeratedValues() {
            assertThat(OptionInputType.TEXT.hasEnumeratedValues()).isFalse();
            assertThat(OptionInputType.SELECT.hasEnumeratedValues()).isTrue();
            assertThat(OptionInputType.SWATCH.hasEnumeratedValues()).isTrue();
        }
    }
}
