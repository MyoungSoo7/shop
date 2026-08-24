package github.lms.lemuel.common.outbox.application.service;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 현재 trace context 를 W3C traceparent 문자열로 직렬화하는 헬퍼.
 *
 * <p>Outbox 이벤트가 도메인 트랜잭션 시점의 trace 를 영속화할 수 있게 한다 — 이후 폴러가
 * 비동기 발행할 때 같은 traceId 를 Kafka 헤더로 복원해 컨슈머도 같은 trace 에 합류 가능.
 *
 * <p>{@code Tracer} 빈이 없는 환경 (트레이싱 비활성) 에서는 null 반환 → 기존 동작 유지.
 *
 * <p>형식: {@code "00-{32hex traceId}-{16hex spanId}-{flags}"} — W3C Trace Context spec.
 */
@Component
public class TraceContextCapture {

    private final Tracer tracer;

    @Autowired(required = false)
    public TraceContextCapture(Tracer tracer) {
        this.tracer = tracer;
    }

    public TraceContextCapture() {
        this.tracer = null;
    }

    /**
     * 현재 활성 span 의 trace context 를 W3C traceparent 형식으로 직렬화.
     * 활성 span 이 없거나 tracer 가 비활성이면 null.
     */
    public String captureCurrentTraceParent() {
        if (tracer == null) return null;
        var current = tracer.currentSpan();
        if (current == null) return null;
        TraceContext ctx = current.context();
        return formatTraceParent(ctx.traceId(), ctx.spanId(), ctx.sampled() != null && ctx.sampled() ? "01" : "00");
    }

    static String formatTraceParent(String traceId, String spanId, String flags) {
        if (traceId == null || spanId == null) return null;
        // W3C: traceId 32hex, spanId 16hex. micrometer-tracing 이 이미 hex 형식 반환.
        String tid = padTo(traceId, 32);
        String sid = padTo(spanId, 16);
        return "00-" + tid + "-" + sid + "-" + flags;
    }

    private static String padTo(String hex, int len) {
        if (hex.length() >= len) return hex;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len - hex.length(); i++) sb.append('0');
        sb.append(hex);
        return sb.toString();
    }
}
