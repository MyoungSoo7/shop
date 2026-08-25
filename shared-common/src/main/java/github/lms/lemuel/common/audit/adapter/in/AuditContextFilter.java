package github.lms.lemuel.common.audit.adapter.in;

import github.lms.lemuel.common.audit.application.AuditContext;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청별 {@link AuditContext} 를 populate 하는 필터.
 *
 * <p>JWT 인증 필터 이후에 실행되어야 actor 정보가 채워진다.
 * 순서: TraceIdFilter(최상위) → JwtAuthenticationFilter → AuditContextFilter.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class AuditContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            AuditContext.set(resolveActor(request));
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    private AuditContext.AuditActor resolveActor(HttpServletRequest request) {
        String email = null;
        Long actorId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            Object principal = auth.getPrincipal();
            email = principal != null ? principal.toString() : null;
            // audit_logs.actor_id 는 오래 비어 있었다. 토큰에 사용자 id 가 실려 있는데도 이메일만
            // 채웠기 때문인데, 이메일은 바뀌고 재사용되는 값이라 몇 달 뒤 "이 조작을 한 사람"을
            // 되짚는 키로는 약하다. 구 토큰(uid claim 없음)이면 여전히 null 이고 그건 정상이다.
            if (principal instanceof AuthPrincipal authPrincipal) {
                actorId = authPrincipal.userId();
            }
        }
        String ip = extractIp(request);
        String ua = request.getHeader("User-Agent");
        return new AuditContext.AuditActor(actorId, email, ip, ua);
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 다중 프록시 체인 시 첫 번째 값이 원 클라이언트
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
