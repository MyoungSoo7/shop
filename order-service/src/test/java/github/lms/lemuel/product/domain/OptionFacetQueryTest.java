package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptionFacetQuery — 파셋 필터 의미 규칙")
class OptionFacetQueryTest {

    @Nested
    @DisplayName("파싱")
    class Parse {

        @Test
        @DisplayName("같은 축의 여러 값은 한 축으로 묶인다 (축 내 OR)")
        void groupsValuesByAxis() {
            OptionFacetQuery q = OptionFacetQuery.of(List.of("색상:빨강", "색상:파랑", "사이즈:L"));

            assertThat(q.axisCount()).isEqualTo(2);
            assertThat(q.axisCodes()).containsExactly("색상", "사이즈");
            assertThat(q.valueCodesOf("색상")).containsExactly("빨강", "파랑");
            assertThat(q.valueCodesOf("사이즈")).containsExactly("L");
        }

        @Test
        @DisplayName("축 수가 곧 AND 조건 수다 — SKU 하나가 이만큼의 축을 모두 만족해야 한다")
        void axisCountIsAndArity() {
            assertThat(OptionFacetQuery.of(List.of("색상:빨강", "색상:파랑")).axisCount()).isEqualTo(1);
            assertThat(OptionFacetQuery.of(List.of("색상:빨강", "사이즈:L", "각인:AB")).axisCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("같은 값을 두 번 보내도 한 번으로 취급한다")
        void deduplicates() {
            assertThat(OptionFacetQuery.of(List.of("색상:빨강", "색상:빨강")).valueCodesOf("색상"))
                    .containsExactly("빨강");
        }

        @Test
        @DisplayName("공백은 코드 규칙대로 하이픈으로 접힌다 — 표시명을 보내도 같은 축을 가리킨다")
        void normalizesWithOptionCodeRule() {
            OptionFacetQuery q = OptionFacetQuery.of(List.of("메인 색상:밝은 빨강"));

            assertThat(q.axisCodes()).containsExactly("메인-색상");
            assertThat(q.valueCodesOf("메인-색상")).containsExactly("밝은-빨강");
        }

        @Test
        @DisplayName("빈 입력은 빈 질의다")
        void emptyInput() {
            assertThat(OptionFacetQuery.of(null).isEmpty()).isTrue();
            assertThat(OptionFacetQuery.of(List.of()).isEmpty()).isTrue();
            assertThat(OptionFacetQuery.empty().axisCount()).isZero();
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"색상", "색상:빨강:추가", "", "   "})
        @DisplayName("'축:값' 형식이 아니면 거부한다")
        void rejectsMalformed(String token) {
            assertThatThrownBy(() -> OptionFacetQuery.of(List.of(token)))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("빈 축·값은 거부한다")
        void rejectsBlankParts() {
            assertThatThrownBy(() -> OptionFacetQuery.of(List.of(":빨강")))
                    .isInstanceOf(ProductInvariantViolationException.class);
            assertThatThrownBy(() -> OptionFacetQuery.of(List.of("색상:")))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("축 제외")
    class Without {

        @Test
        @DisplayName("자기 축 선택을 빼면 형제 값을 추가로 고를 수 있다")
        void removesOneAxis() {
            OptionFacetQuery q = OptionFacetQuery.of(List.of("색상:빨강", "사이즈:L"));

            OptionFacetQuery withoutColor = q.without("색상");

            assertThat(withoutColor.axisCount()).isEqualTo(1);
            assertThat(withoutColor.axisCodes()).containsExactly("사이즈");
            assertThat(q.axisCount()).isEqualTo(2); // 원본 불변
        }

        @Test
        @DisplayName("없는 축을 빼면 그대로다")
        void unknownAxisIsNoop() {
            OptionFacetQuery q = OptionFacetQuery.of(List.of("색상:빨강"));

            assertThat(q.without("사이즈")).isSameAs(q);
        }

        @Test
        @DisplayName("마지막 축을 빼면 빈 질의가 된다")
        void removingLastAxisEmpties() {
            assertThat(OptionFacetQuery.of(List.of("색상:빨강")).without("색상").isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("쌍 목록은 IN 절용으로 평탄화된다")
    void flattensPairs() {
        assertThat(OptionFacetQuery.of(List.of("색상:빨강", "색상:파랑", "사이즈:L")).pairs())
                .containsExactly(
                        new OptionFacetQuery.AxisValue("색상", "빨강"),
                        new OptionFacetQuery.AxisValue("색상", "파랑"),
                        new OptionFacetQuery.AxisValue("사이즈", "L"));
    }
}
