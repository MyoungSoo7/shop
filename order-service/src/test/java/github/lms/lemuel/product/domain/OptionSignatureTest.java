package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.OptionSignature.AxisSelection;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptionSignature — 옵션 조합의 정규화 서명")
class OptionSignatureTest {

    @Nested
    @DisplayName("정규화")
    class Canonicalization {

        @Test
        @DisplayName("선택 순서가 달라도 같은 조합이면 같은 서명이다 — 정렬이 규칙의 핵심")
        void orderIndependent() {
            String a = OptionSignature.of(List.of(
                    new AxisSelection(1L, 10L), new AxisSelection(2L, 20L)));
            String b = OptionSignature.of(List.of(
                    new AxisSelection(2L, 20L), new AxisSelection(1L, 10L)));

            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("값이 하나만 달라도 서명이 달라진다")
        void differentValueDiffersSignature() {
            String red = OptionSignature.of(List.of(
                    new AxisSelection(1L, 10L), new AxisSelection(2L, 20L)));
            String blue = OptionSignature.of(List.of(
                    new AxisSelection(1L, 11L), new AxisSelection(2L, 20L)));

            assertThat(red).isNotEqualTo(blue);
        }

        @Test
        @DisplayName("축이 하나 더 붙으면 서명이 달라진다 — 부분 선택이 전체 선택과 충돌하지 않는다")
        void additionalAxisDiffersSignature() {
            String twoAxes = OptionSignature.of(List.of(
                    new AxisSelection(1L, 10L), new AxisSelection(2L, 20L)));
            String threeAxes = OptionSignature.of(List.of(
                    new AxisSelection(1L, 10L), new AxisSelection(2L, 20L),
                    new AxisSelection(3L, 30L)));

            assertThat(twoAxes).isNotEqualTo(threeAxes);
        }

        @Test
        @DisplayName("축·값 id 를 뒤바꾸면 다른 서명이다 — 구분자가 필드를 실제로 가른다")
        void fieldsAreNotAmbiguous() {
            String ab = OptionSignature.of(List.of(new AxisSelection(1L, 210L)));
            String ba = OptionSignature.of(List.of(new AxisSelection(12L, 10L)));

            assertThat(ab).isNotEqualTo(ba);
        }

        @Test
        @DisplayName("서명은 64 자 소문자 hex 다 — VARCHAR(64) 에 그대로 들어간다")
        void isSha256Hex() {
            String signature = OptionSignature.of(List.of(new AxisSelection(1L, 10L)));

            assertThat(signature).hasSize(64).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("같은 입력은 항상 같은 서명이다")
        void deterministic() {
            List<AxisSelection> selections = List.of(new AxisSelection(7L, 70L));

            assertThat(OptionSignature.of(selections))
                    .isEqualTo(OptionSignature.of(selections));
        }
    }

    @Nested
    @DisplayName("거부")
    class Rejections {

        @Test
        @DisplayName("빈 선택은 거부한다")
        void rejectsEmpty() {
            assertThatThrownBy(() -> OptionSignature.of(List.of()))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("없습니다");
        }

        @Test
        @DisplayName("null 선택은 거부한다")
        void rejectsNull() {
            assertThatThrownBy(() -> OptionSignature.of(null))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("한 축을 두 번 선택하면 거부한다 — '빨강이면서 파랑' 은 조합이 아니다")
        void rejectsDuplicateAxis() {
            assertThatThrownBy(() -> OptionSignature.of(List.of(
                    new AxisSelection(1L, 10L), new AxisSelection(1L, 11L))))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("두 번");
        }

        @Test
        @DisplayName("AxisSelection 의 id 는 null 일 수 없다")
        void rejectsNullIds() {
            assertThatThrownBy(() -> new AxisSelection(null, 1L))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new AxisSelection(1L, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("목록 오버로드")
    class ListOverload {

        @Test
        @DisplayName("축·값 목록으로도 같은 서명을 만든다")
        void matchesSelectionForm() {
            assertThat(OptionSignature.of(List.of(1L, 2L), List.of(10L, 20L)))
                    .isEqualTo(OptionSignature.of(List.of(
                            new AxisSelection(1L, 10L), new AxisSelection(2L, 20L))));
        }

        @Test
        @DisplayName("길이가 다르면 거부한다")
        void rejectsLengthMismatch() {
            assertThatThrownBy(() -> OptionSignature.of(List.of(1L, 2L), List.of(10L)))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("개수");
        }

        @Test
        @DisplayName("null 목록은 거부한다")
        void rejectsNullLists() {
            assertThatThrownBy(() -> OptionSignature.of(null, List.of(10L)))
                    .isInstanceOf(ProductInvariantViolationException.class);
            assertThatThrownBy(() -> OptionSignature.of(List.of(1L), null))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("코드 변환 규칙")
    class Codes {

        @Test
        @DisplayName("내부 공백을 하이픈으로 접는다")
        void collapsesWhitespace() {
            assertThat(OptionCode.fromDisplayName("  메인   색상 ", "옵션 축 이름"))
                    .isEqualTo("메인-색상");
        }

        @Test
        @DisplayName("50 자는 허용, 51 자는 거부한다 (경계)")
        void enforcesLengthBoundary() {
            assertThat(OptionCode.fromDisplayName("가".repeat(50), "옵션 축 이름")).hasSize(50);

            assertThatThrownBy(() -> OptionCode.fromDisplayName("가".repeat(51), "옵션 축 이름"))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("50");
        }

        @Test
        @DisplayName("빈 이름은 거부한다")
        void rejectsBlank() {
            assertThatThrownBy(() -> OptionCode.fromDisplayName("  ", "옵션 축 이름"))
                    .isInstanceOf(ProductInvariantViolationException.class);
            assertThatThrownBy(() -> OptionCode.fromDisplayName(null, "옵션 축 이름"))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }
    }
}
