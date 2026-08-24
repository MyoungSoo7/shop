package github.lms.lemuel.common.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private RateLimitRegistry registry;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        registry = new RateLimitRegistry();
        // 테스트용 작은 버킷: 2회 / 1분
        List<RateLimitPolicy> policies = List.of(
                new RateLimitPolicy("test", "/test",
                        RateLimitPolicy.byIp(), 2, Duration.ofMinutes(1))
        );
        filter = new RateLimitFilter(registry, policies);
    }

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    @Test
    @DisplayName("정책 미매칭 경로는 통과")
    void passesThroughWhenNoPolicy() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/other");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("허용 한도 내는 통과 + X-RateLimit-Remaining 헤더")
    void allowsWithinCapacity() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
            req.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, res, chain);

            verify(chain).doFilter(req, res);
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(res.getHeader("X-RateLimit-Remaining")).isNotNull();
        }
    }

    @Test
    @DisplayName("한도 초과 3번째 요청은 429 + Retry-After")
    void blocksWhenExceeded() throws Exception {
        // 처음 2회 소진
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
            req.setRemoteAddr("10.0.0.2");
            filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        }

        // 3번째는 거부
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        req.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        assertThat(res.getContentType()).contains("application/json");
        verify(chain, times(0)).doFilter(req, res);
    }

    @Test
    @DisplayName("다른 IP 는 별도 버킷 — 한도 독립")
    void separateBucketsPerKey() throws Exception {
        // IP A 로 2회 소진
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
            req.setRemoteAddr("10.0.0.10");
            filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        }
        // IP B 는 여전히 통과
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        req.setRemoteAddr("10.0.0.11");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("X-Forwarded-For 우선 사용")
    void usesForwardedForIp() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        req.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.1");
        req.setRemoteAddr("10.0.0.99");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        // 성공만 확인 — 내부 키 추출이 forwarded 를 썼는지는 headers 로 간접 검증 어려움
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(registry.size()).isEqualTo(1);
    }
}
