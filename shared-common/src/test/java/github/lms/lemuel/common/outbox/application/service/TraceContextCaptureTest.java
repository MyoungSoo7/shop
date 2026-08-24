package github.lms.lemuel.common.outbox.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextCaptureTest {

    @Test
    @DisplayName("Tracer 가 null 이면 captureCurrentTraceParent() 는 null 반환 — 트레이싱 비활성 환경 호환")
    void noTracer_returnsNull() {
        var capture = new TraceContextCapture();

        String tp = capture.captureCurrentTraceParent();

        assertThat(tp).isNull();
    }

    @Test
    @DisplayName("formatTraceParent: 표준 traceId+spanId 를 W3C 형식으로 직렬화")
    void format_standard() {
        String tp = TraceContextCapture.formatTraceParent(
                "0123456789abcdef0123456789abcdef", "fedcba9876543210", "01");

        assertThat(tp).isEqualTo("00-0123456789abcdef0123456789abcdef-fedcba9876543210-01");
    }

    @Test
    @DisplayName("formatTraceParent: 짧은 hex 는 leading 0 으로 32/16 자 패딩 (W3C 길이 강제)")
    void format_padShortHex() {
        String tp = TraceContextCapture.formatTraceParent("abc", "def", "00");

        // W3C: "00-{32hex traceId}-{16hex spanId}-{2hex flags}" → 총 55 자
        assertThat(tp).isEqualTo("00-00000000000000000000000000000abc-0000000000000def-00");
        assertThat(tp).hasSize(55);
    }

    @Test
    @DisplayName("formatTraceParent: traceId 또는 spanId null → null")
    void format_null() {
        assertThat(TraceContextCapture.formatTraceParent(null, "abc", "01")).isNull();
        assertThat(TraceContextCapture.formatTraceParent("abc", null, "01")).isNull();
    }
}
