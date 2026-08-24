package github.lms.lemuel.common.config.jwt;

import github.lms.lemuel.common.audit.adapter.in.AuditContextFilter;
import github.lms.lemuel.common.audit.application.AuditContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT/내부키/감사컨텍스트 서블릿 필터의 doFilter 분기를 MockHttpServletRequest 로 직접 구동한다.
 */
class SecurityFiltersTest {

    private final JwtUtil jwtUtil = newJwtUtil();

    private static JwtUtil newJwtUtil() {
        JwtProperties props = new JwtProperties();
        props.setIssuer("test");
        props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
        props.setTtlSeconds(3600);
        return new JwtUtil(props);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AuditContext.clear();
    }

    // ─── JwtAuthenticationFilter ─────────────────────────────────────────────

    @Test
    @DisplayName("유효한 Bearer 토큰 → SecurityContext 에 AuthPrincipal 설정")
    void jwtFilterAuthenticatesValidToken() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil);
        String token = jwtUtil.generateToken("user@test.com", "USER");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("user@test.com");
        assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_USER"));
    }

    @Test
    @DisplayName("잘못된 토큰은 인증 없이 통과(Security 가 401 처리)")
    void jwtFilterIgnoresInvalidToken() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.addHeader("Authorization", "Bearer not.a.jwt");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("이미 인증된 컨텍스트면 필터를 건너뛴다")
    void jwtFilterSkipsWhenAlreadyAuthenticated() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil);
        Authentication existing = mock(Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(existing);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.addHeader("Authorization", "Bearer " + jwtUtil.generateToken("a@b.com", "USER"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    }

    @Test
    @DisplayName("shouldNotFilter: 공개 경로는 필터 대상에서 제외")
    void jwtFilterShouldNotFilterPublicPaths() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil);
        assertThat(shouldNotFilter(filter, "/auth/login")).isTrue();
        assertThat(shouldNotFilter(filter, "/actuator/health")).isTrue();
        assertThat(shouldNotFilter(filter, "/swagger-ui/index.html")).isTrue();
        assertThat(shouldNotFilter(filter, "/v3/api-docs")).isTrue();
        assertThat(shouldNotFilter(filter, "/auth/dev/guest")).isTrue();
        assertThat(shouldNotFilter(filter, "/users")).isTrue();
        assertThat(shouldNotFilter(filter, "/orders")).isFalse();
    }

    private static boolean shouldNotFilter(JwtAuthenticationFilter filter, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        try {
            var m = org.springframework.web.filter.OncePerRequestFilter.class
                    .getDeclaredMethod("shouldNotFilter", jakarta.servlet.http.HttpServletRequest.class);
            m.setAccessible(true);
            return (boolean) m.invoke(filter, req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── InternalApiKeyFilter ────────────────────────────────────────────────

    @Test
    @DisplayName("키 설정 + 헤더 불일치 → 401")
    void internalKeyMismatchRejects() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/recon");
        req.setServletPath("/internal/recon");
        req.addHeader(InternalApiKeyFilter.HEADER, "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    @DisplayName("키 설정 + 헤더 일치 → 통과")
    void internalKeyMatchPasses() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/recon");
        req.setServletPath("/internal/recon");
        req.addHeader(InternalApiKeyFilter.HEADER, "secret-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("키 미설정 → 경고 후 통과(개발 호환)")
    void internalKeyMissingPassesWithWarn() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/recon");
        req.setServletPath("/internal/recon");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);
        // 두 번째 호출도 통과(warnedMissingKey 분기)
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain2);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain2.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("키 미설정 + keyRequired=true(운영) → 401 fail-closed")
    void internalKeyMissingButRequiredRejects() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("", true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/recon");
        req.setServletPath("/internal/recon");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    @DisplayName("VAN 경로(/van/**)도 같은 공유 시크릿으로 보호된다 — 헤더 불일치는 401")
    void vanPathIsGuardedByInternalKey() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/van/v1/authorizations");
        req.setServletPath("/van/v1/authorizations");
        req.addHeader(InternalApiKeyFilter.HEADER, "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    @DisplayName("servletPath 가 비어도 requestURI 로 보호 경로를 판정한다 — 게이트가 조용히 꺼지지 않는다")
    void guardFallsBackToRequestUriWhenServletPathEmpty() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/recon/settlements");
        req.setServletPath("");                       // 디스패처 매핑에 따라 실제로 빈 값이 온다
        req.setRequestURI("/internal/recon/settlements");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    @DisplayName("컨텍스트 경로가 붙어도 보호 경로 판정이 유지된다")
    void guardStripsContextPathOnFallback() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/app/van/v1/captures");
        req.setServletPath("");
        req.setContextPath("/app");
        req.setRequestURI("/app/van/v1/captures");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    @DisplayName("비-내부 경로는 키 검사 없이 통과")
    void internalKeyIgnoresNonInternalPath() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.setServletPath("/orders");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    // ─── AuditContextFilter ──────────────────────────────────────────────────

    @Test
    @DisplayName("인증된 actor + X-Forwarded-For 체인에서 원 클라이언트 IP 추출")
    void auditContextResolvesActorAndIp() throws Exception {
        AuditContextFilter filter = new AuditContextFilter();
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn("admin@test.com");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/settlements");
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        req.addHeader("User-Agent", "curl/8.0");

        AuditContext.AuditActor[] captured = new AuditContext.AuditActor[1];
        FilterChain chain = (rq, rs) -> captured[0] = AuditContext.get();
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(captured[0].actorEmail()).isEqualTo("admin@test.com");
        assertThat(captured[0].ipAddress()).isEqualTo("203.0.113.7");
        assertThat(captured[0].userAgent()).isEqualTo("curl/8.0");
        // finally 블록이 clear 했는지
        assertThat(AuditContext.get().actorEmail()).isNull();
    }

    @Test
    @DisplayName("익명 사용자면 actor email 은 null, remoteAddr 로 IP 결정")
    void auditContextAnonymousUsesRemoteAddr() throws Exception {
        AuditContextFilter filter = new AuditContextFilter();
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn("anonymousUser");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.setRemoteAddr("192.168.0.9");

        AuditContext.AuditActor[] captured = new AuditContext.AuditActor[1];
        FilterChain chain = (rq, rs) -> captured[0] = AuditContext.get();
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(captured[0].actorEmail()).isNull();
        assertThat(captured[0].ipAddress()).isEqualTo("192.168.0.9");
    }

    // ─── AuthPrincipal ───────────────────────────────────────────────────────

    @Test
    @DisplayName("AuthPrincipal: getName/toString 은 email, userId/role 접근자 노출")
    void authPrincipalContract() {
        AuthPrincipal p = new AuthPrincipal(42L, "u@test.com", "ADMIN");
        assertThat(p.getName()).isEqualTo("u@test.com");
        assertThat(p.toString()).isEqualTo("u@test.com");
        assertThat(p.userId()).isEqualTo(42L);
        assertThat(p.role()).isEqualTo("ADMIN");
    }
}
