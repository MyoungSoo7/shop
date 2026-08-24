package github.lms.lemuel.operation.board.adapter.out.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정화 어댑터 테스트 — <b>실행 가능한 것은 남지 않는다</b>가 유일한 기준이다.
 *
 * <p>여기 나열한 페이로드는 XSS 필터 우회의 고전들이다. 하나씩 막는 블랙리스트로는 계속 새 형태가
 * 나오므로 구현은 화이트리스트여야 하고, 이 테스트는 그 화이트리스트가 실제로 좁은지를 확인한다.
 */
class JsoupHtmlSanitizerAdapterTest {

    private final JsoupHtmlSanitizerAdapter adapter = new JsoupHtmlSanitizerAdapter();

    @ParameterizedTest
    @DisplayName("스크립트 실행 벡터는 남지 않는다")
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<a href=\"javascript:alert(1)\">click</a>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<svg/onload=alert(1)>",
            "<body onload=alert(1)>hi</body>",
            "<object data=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\"></object>",
            "<style>body{background:url('javascript:alert(1)')}</style>",
            "<form action=\"/steal\"><input name=\"pw\"></form>",
    })
    void removesExecutableVectors(String payload) {
        String cleaned = adapter.sanitize(payload);

        assertThat(cleaned.toLowerCase())
                .doesNotContain("<script")
                .doesNotContain("onerror")
                .doesNotContain("onload")
                .doesNotContain("javascript:")
                .doesNotContain("<iframe")
                .doesNotContain("<object")
                .doesNotContain("<style")
                .doesNotContain("<form")
                .doesNotContain("<input");
    }

    @Test
    @DisplayName("표시용 마크업은 살아남는다 — 정화가 게시판을 못 쓰게 만들면 안 된다")
    void keepsDisplayMarkup() {
        String cleaned = adapter.sanitize(
                "<p>안녕하세요 <strong>굵게</strong> <em>기울임</em></p>"
                        + "<ul><li>항목</li></ul>"
                        + "<a href=\"https://example.com\">링크</a>"
                        + "<img src=\"https://example.com/a.png\" alt=\"그림\">"
                        + "<pre><code>System.out.println();</code></pre>");

        assertThat(cleaned)
                .contains("<strong>굵게</strong>")
                .contains("<em>기울임</em>")
                .contains("<li>항목</li>")
                .contains("href=\"https://example.com\"")
                .contains("src=\"https://example.com/a.png\"")
                .contains("<code>");
    }

    @Test
    @DisplayName("텍스트는 보존하고 태그만 걷어낸다 — 본문이 통째로 사라지지 않는다")
    void keepsTextWhenStrippingTags() {
        assertThat(adapter.sanitize("<script>alert(1)</script>본문은 남는다"))
                .contains("본문은 남는다");
    }

    @Test
    @DisplayName("null·빈 값은 그대로 통과한다")
    void nullSafe() {
        assertThat(adapter.sanitize(null)).isNull();
        assertThat(adapter.sanitize("")).isEmpty();
    }

    @Test
    @DisplayName("정화 결과를 다시 정화해도 더 바뀌지 않는다(멱등) — 수정 시 본문이 계속 깎이지 않게")
    void isIdempotent() {
        String once = adapter.sanitize("<p>본문 <strong>강조</strong></p><script>alert(1)</script>");

        assertThat(adapter.sanitize(once)).isEqualTo(once);
    }
}
