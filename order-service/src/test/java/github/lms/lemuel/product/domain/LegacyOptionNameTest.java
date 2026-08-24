package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.LegacyOptionName.Segment;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LegacyOptionName — \"색상:빨강/사이즈:L\" 규약 파서")
class LegacyOptionNameTest {

    @Nested
    @DisplayName("파싱")
    class Parse {

        @Test
        @DisplayName("두 차수를 순서대로 분해한다")
        void parsesTwoAxes() {
            List<Segment> segments = LegacyOptionName.parse("색상:빨강/사이즈:L");

            assertThat(segments).containsExactly(
                    new Segment("색상", "빨강"),
                    new Segment("사이즈", "L"));
        }

        @Test
        @DisplayName("단일 차수도 파싱한다")
        void parsesSingleAxis() {
            assertThat(LegacyOptionName.parse("색상:빨강"))
                    .containsExactly(new Segment("색상", "빨강"));
        }

        @Test
        @DisplayName("세 차수 이상도 파싱한다 — 차수 상한이 없다")
        void parsesThreeAxes() {
            assertThat(LegacyOptionName.parse("색상:빨강/사이즈:L/각인:AB"))
                    .hasSize(3)
                    .last()
                    .isEqualTo(new Segment("각인", "AB"));
        }

        @Test
        @DisplayName("차수 주변 공백은 제거한다")
        void trimsWhitespace() {
            assertThat(LegacyOptionName.parse(" 색상 : 빨강 / 사이즈 : L "))
                    .containsExactly(new Segment("색상", "빨강"), new Segment("사이즈", "L"));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("빈 표시명은 거부한다")
        void rejectsBlank(String optionName) {
            assertThatThrownBy(() -> LegacyOptionName.parse(optionName))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("비어");
        }

        @Test
        @DisplayName("null 은 거부한다")
        void rejectsNull() {
            assertThatThrownBy(() -> LegacyOptionName.parse(null))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("콜론이 없는 차수는 거부한다")
        void rejectsMissingColon() {
            assertThatThrownBy(() -> LegacyOptionName.parse("색상빨강"))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("축:값");
        }

        @Test
        @DisplayName("콜론이 둘 이상인 차수는 거부한다 — 첫 콜론 분할은 조용한 오해석을 낳는다")
        void rejectsMultipleColons() {
            assertThatThrownBy(() -> LegacyOptionName.parse("각인:A:B"))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("축:값");
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"색상:빨강/", "/색상:빨강", "색상:빨강//사이즈:L"})
        @DisplayName("빈 차수를 만드는 구분자는 거부한다")
        void rejectsEmptySegment(String optionName) {
            assertThatThrownBy(() -> LegacyOptionName.parse(optionName))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("빈 차수");
        }

        @Test
        @DisplayName("축 이름이 비면 거부한다")
        void rejectsBlankAxisName() {
            assertThatThrownBy(() -> LegacyOptionName.parse(":빨강"))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("축 이름");
        }

        @Test
        @DisplayName("값 이름이 비면 거부한다")
        void rejectsBlankValueName() {
            assertThatThrownBy(() -> LegacyOptionName.parse("색상:"))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("값 이름");
        }

        @Test
        @DisplayName("같은 축이 두 번 나오면 거부한다 — 축당 값 1 개 불변식")
        void rejectsDuplicateAxis() {
            assertThatThrownBy(() -> LegacyOptionName.parse("색상:빨강/색상:파랑"))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("두 번");
        }

        @Test
        @DisplayName("반환 목록은 불변이다")
        void returnsImmutableList() {
            List<Segment> segments = LegacyOptionName.parse("색상:빨강");

            assertThatThrownBy(() -> segments.add(new Segment("사이즈", "L")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("포매팅")
    class Format {

        @Test
        @DisplayName("파싱과 왕복한다")
        void roundTrips() {
            String original = "색상:빨강/사이즈:L";

            assertThat(LegacyOptionName.format(LegacyOptionName.parse(original)))
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("빈 목록은 거부한다")
        void rejectsEmpty() {
            assertThatThrownBy(() -> LegacyOptionName.format(List.of()))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("null 목록은 거부한다")
        void rejectsNull() {
            assertThatThrownBy(() -> LegacyOptionName.format(null))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("Segment")
    class SegmentValidation {

        @Test
        @DisplayName("생성 시 공백을 제거한다")
        void trims() {
            assertThat(new Segment(" 색상 ", " 빨강 "))
                    .isEqualTo(new Segment("색상", "빨강"));
        }

        @Test
        @DisplayName("축 이름 null 을 거부한다")
        void rejectsNullAxisName() {
            assertThatThrownBy(() -> new Segment(null, "빨강"))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("값 이름 null 을 거부한다")
        void rejectsNullValueName() {
            assertThatThrownBy(() -> new Segment("색상", null))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }
    }
}
