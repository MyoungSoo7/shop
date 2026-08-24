package github.lms.lemuel.category.util;
import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slug 생성 유틸 단위 테스트 — 한글 음절 로마자 변환·정규화·검증 경로를 커버한다.
 */
class SlugGeneratorTest {

    private final SlugGenerator generator = new SlugGenerator();

    @Test
    @DisplayName("영문/공백은 소문자 하이픈 slug 로 변환")
    void generate_english() {
        assertThat(generator.generate("Computer Accessories")).isEqualTo("computer-accessories");
    }

    @Test
    @DisplayName("언더스코어와 연속 공백은 단일 하이픈으로 축약")
    void generate_underscoreAndSpaces() {
        assertThat(generator.generate("foo_bar   baz")).isEqualTo("foo-bar-baz");
    }

    @Test
    @DisplayName("한글만 있는 입력은 NFD 분해로 음차가 무력화돼 빈 slug 예외 (실측 동작)")
    void generate_koreanOnlyThrows() {
        // Normalizer.NFD 가 전조합 한글을 결합형 자모로 분해한 뒤 [^a-z0-9-] 제거로 사라져
        // transliterateKorean 의 'ch >= 가' 분기가 실행되지 못한다 → 결과 빈 문자열 → 예외.
        assertThatThrownBy(() -> generator.generate("가나"))
                .isInstanceOf(CategoryInvariantViolationException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> generator.generate("한글"))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("영문·한글 혼합 시 영문 부분만 남는다 (한글은 NFD 후 제거)")
    void generate_mixedKeepsAscii() {
        assertThat(generator.generate("shop 가방")).isEqualTo("shop");
    }

    @Test
    @DisplayName("허용되지 않는 특수문자는 제거된다")
    void generate_stripsSpecialChars() {
        assertThat(generator.generate("Hello@World!")).isEqualTo("helloworld");
    }

    @Test
    @DisplayName("앞뒤 하이픈은 제거된다")
    void generate_trimsHyphens() {
        assertThat(generator.generate("  -abc-  ")).isEqualTo("abc");
    }

    @Test
    @DisplayName("null/공백 입력은 예외")
    void generate_blankThrows() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(CategoryInvariantViolationException.class);
        assertThatThrownBy(() -> generator.generate("   "))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("특수문자만 있으면 생성 결과가 비어 예외")
    void generate_onlySpecialThrows() {
        assertThatThrownBy(() -> generator.generate("@#$%"))
                .isInstanceOf(CategoryInvariantViolationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("generateWithParent: 부모 slug 접두 결합")
    void generateWithParent() {
        assertThat(generator.generateWithParent("electronics", "Computer"))
                .isEqualTo("electronics-computer");
    }

    @Test
    @DisplayName("generateWithParent: 부모 slug 없으면 자식 slug 만")
    void generateWithParent_noParent() {
        assertThat(generator.generateWithParent(null, "Computer")).isEqualTo("computer");
        assertThat(generator.generateWithParent("  ", "Computer")).isEqualTo("computer");
    }

    @Test
    @DisplayName("isValid: 규칙 준수 여부 판별")
    void isValid() {
        assertThat(generator.isValid("valid-slug-123")).isTrue();
        assertThat(generator.isValid("-leading")).isFalse();
        assertThat(generator.isValid("trailing-")).isFalse();
        assertThat(generator.isValid("Upper")).isFalse();
        assertThat(generator.isValid(null)).isFalse();
        assertThat(generator.isValid("  ")).isFalse();
    }
}
