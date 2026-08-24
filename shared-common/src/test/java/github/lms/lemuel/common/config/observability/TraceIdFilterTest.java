package github.lms.lemuel.common.config.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("헤더 없으면 UUID 생성 + 응답 헤더 에코")
    void generatesTraceIdWhenMissing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        String traceId = res.getHeader(TraceIdFilter.HEADER_TRACE_ID);
        assertThat(traceId).isNotBlank();
        assertThat(traceId).matches("[0-9a-f-]{36}");
        verify(chain).doFilter(req, res);
        // 필터 종료 후 MDC 는 정리됨
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    @DisplayName("헤더 있으면 재사용 — upstream traceId 와 체인")
    void reusesTraceIdFromHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceIdFilter.HEADER_TRACE_ID, "trace-abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo("trace-abc-123");
    }

    @Test
    @DisplayName("필터 실행 도중 MDC 에 traceId 존재")
    void mdcSetDuringChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceIdFilter.HEADER_TRACE_ID, "abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isEqualTo("abc");
        };

        filter.doFilter(req, res, chain);
        // 종료 후 정리 확인
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }
}
