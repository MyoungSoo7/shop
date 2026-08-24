package github.lms.lemuel.common.config.jwt;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 이미 인증이 설정된 경우 필터 스킵 (@WithMockUser 등 테스트 호환)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            try {
                // 토큰 파싱 1회로 통합 (기존: validateToken + getEmail + parseToken = 3회)
                Claims claims = jwtUtil.parseToken(token);
                String email = claims.getSubject();
                String role = claims.get("role", String.class);
                Long uid = claims.get("uid", Long.class); // 구 토큰 호환: null 가능

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                new AuthPrincipal(uid, email, role),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // 유효하지 않은 토큰 — 인증 없이 통과 (Spring Security가 401 처리)
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * ASYNC 디스패치에서도 이 필터를 태운다 — {@code OncePerRequestFilter} 기본값은 건너뛰기다.
     *
     * <p>SSE 처럼 응답을 async 로 완료하는 엔드포인트({@code SseEmitter.complete()})는 컨테이너가
     * ASYNC 디스패치를 한 번 더 돌리고, 그때 Spring Security 필터 체인도 다시 돈다. 이 필터가
     * 건너뛰어지면 STATELESS 라 복구할 세션도 없어 인증이 빈 채로 인가에 도달하고,
     * {@code AccessDeniedException} 이 던져진다 — 응답은 이미 커밋된 뒤라 에러페이지도 못 쓰고
     * <b>chunked 종결자 없이 커넥션이 끊긴다</b>. 클라이언트는 완료를 관측하지 못해
     * 채팅 UI 가 "응답 중…"에 갇힌다(대화는 서버에 저장된 뒤인데도).
     *
     * <p>토큰은 요청 헤더에 그대로 있으므로 재파싱해 인증을 복구하면 인가가 정상 통과한다.
     * 인증이 없던 자리를 채울 뿐이라 기존에 통과하던 요청이 막힐 수는 없다.
     * 회귀 가드: ai-service {@code ChatStreamTerminationIntegrationTest}.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator/health")
            || path.startsWith("/actuator/info")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/v3/api-docs")
            || (path.equals("/auth/login"))
            || (path.startsWith("/auth/dev/"))   // 데모/게스트 토큰 발급 (lemuel.demo.enabled 로 컨트롤러에서 차단)
            || (path.equals("/users"));
    }
}
