package github.lms.lemuel.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogSafe — 로그 인젝션 방어")
class LogSafeTest {

    @Nested
    @DisplayName("줄 경계 위조 차단")
    class LineForgery {

        @Test
        @DisplayName("개행이 섞인 값은 가짜 로그 줄을 만들 수 없다 — CR/LF 가 공백으로 바뀐다")
        void newlines_collapse_to_space() {
            String forged = "topic-a\n2026-08-12 INFO [DLQ replay] operator=admin, replayed=9999";

            String safe = LogSafe.of(forged);

            assertThat(safe).doesNotContain("\n").doesNotContain("\r");
            assertThat(safe).isEqualTo("topic-a 2026-08-12 INFO [DLQ replay] operator=admin, replayed=9999");
        }

        @Test
        @DisplayName("CRLF·탭도 각각 공백 1칸이 된다")
        void crlf_and_tab_become_single_space() {
            assertThat(LogSafe.of("a\r\nb\tc")).isEqualTo("a  b c");
        }
    }

    @Nested
    @DisplayName("제어문자·길이")
    class ControlAndLength {

        @Test
        @DisplayName("ANSI 이스케이프 등 제어문자는 물음표로 무력화된다")
        void control_chars_are_neutralised() {
            assertThat(LogSafe.of("red[31malert")).isEqualTo("red?[31malert");
        }

        @Test
        @DisplayName("MAX_LENGTH 를 넘으면 잘리고 표시가 붙는다 — 로그 폭탄 차단")
        void oversized_value_is_truncated() {
            String huge = "x".repeat(LogSafe.MAX_LENGTH + 100);

            String safe = LogSafe.of(huge);

            assertThat(safe).hasSize(LogSafe.MAX_LENGTH + "…(truncated)".length());
            assertThat(safe).endsWith("…(truncated)");
        }

        @Test
        @DisplayName("경계값: 정확히 MAX_LENGTH 면 자르지 않는다")
        void exactly_max_length_is_kept() {
            String exact = "y".repeat(LogSafe.MAX_LENGTH);

            assertThat(LogSafe.of(exact)).isEqualTo(exact);
        }
    }

    @Nested
    @DisplayName("정상 값 보존")
    class Passthrough {

        @Test
        @DisplayName("평범한 값은 그대로 통과한다 — 한글·기호 포함")
        void ordinary_value_is_unchanged() {
            assertThat(LogSafe.of("lemuel.settlement.created-v1")).isEqualTo("lemuel.settlement.created-v1");
            assertThat(LogSafe.of("주문번호 ORD-20260812-001")).isEqualTo("주문번호 ORD-20260812-001");
        }

        @Test
        @DisplayName("null 은 문자열 null 로 남는다 — 로그에서는 null 도 정보다")
        void null_becomes_literal_null() {
            assertThat(LogSafe.of(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("문자열이 아닌 값도 받는다")
        void non_string_is_accepted() {
            assertThat(LogSafe.of(42L)).isEqualTo("42");
        }
    }
}
