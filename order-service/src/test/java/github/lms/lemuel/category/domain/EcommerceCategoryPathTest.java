package github.lms.lemuel.category.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EcommerceCategory — slug 경로 조립 규칙")
class EcommerceCategoryPathTest {

    private static EcommerceCategory category(String slug) {
        return EcommerceCategory.createRoot("이름", slug, 0);
    }

    @Test
    @DisplayName("부모 경로가 없으면 자기 slug 가 곧 경로다")
    void rootPathIsOwnSlug() {
        assertThat(category("electronics").pathSlugUnder(null)).isEqualTo("electronics");
    }

    @Test
    @DisplayName("빈 부모 경로도 루트로 취급한다")
    void blankParentPathIsRoot() {
        assertThat(category("electronics").pathSlugUnder("  ")).isEqualTo("electronics");
    }

    @Test
    @DisplayName("부모 경로 아래에 '/' 로 이어 붙인다")
    void joinsUnderParent() {
        assertThat(category("laptops").pathSlugUnder("electronics/computers"))
                .isEqualTo("electronics/computers/laptops");
    }

    @Test
    @DisplayName("3 단계 경로를 단계적으로 조립해도 같은 결과가 나온다 — DB 재계산과 같은 규칙")
    void buildsIncrementally() {
        String root = category("electronics").pathSlugUnder(null);
        String mid = category("computers").pathSlugUnder(root);
        String leaf = category("laptops").pathSlugUnder(mid);

        assertThat(leaf).isEqualTo("electronics/computers/laptops");
    }
}
