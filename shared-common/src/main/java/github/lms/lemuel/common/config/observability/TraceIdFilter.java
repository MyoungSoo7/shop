package github.lms.lemuel.common.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청별 traceId 를 MDC 에 주입하는 필터.
 *
 * <p>동작:
 * <ul>
 *   <li>요청 헤더 {@code X-Request-Id} 가 있으면 그것을 traceId 로 사용 (upstream 과 체인).</li>
 *   <li>없으면 {@code UUID.randomUUID()} 로 새로 생성.</li>
 *   <li>응답 헤더에 traceId 에코 → 클라이언트·게이트웨이가 추적 가능.</li>
 *   <li>finally 에서 MDC.clear() — 쓰레드 풀 재사용 시 누수 방지.</li>
 * </ul>
 *
 * <p>로그 포맷(logback-spring.xml)은 MDC 의 {@code traceId} 를 읽어 모든 라인에 부착.
 * 결제→정산→환불 체인 디버깅 시 traceId 로 grep 하면 전 구간이 연결된다.
 *
 * <p>Filter 순서는 {@link Ordered#HIGHEST_PRECEDENCE} — 다른 필터(JWT, Security)가
 * 로그를 쌓기 전에 traceId 가 MDC 에 들어가 있어야 한다.
 */
/**
 * {@code ObservabilityConfig} 에서 FilterRegistrationBean 으로 등록.
 * WebMvcTest 슬라이스에서 자동 등록되지 않아 슬라이스 테스트에 영향을 주지 않는다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String HEADER_TRACE_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_TRACE_ID, traceId);
            response.setHeader(HEADER_TRACE_ID, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
