package github.lms.lemuel.common.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 경로별 rate limit 를 강제하는 필터.
 *
 * <p>정책(기본):
 * <ul>
 *   <li>/auth/login — IP 기준 5 req / min</li>
 *   <li>/payments (환불 포함) — actor 기준 10 req / min</li>
 *   <li>/admin — actor 기준 30 req / min</li>
 * </ul>
 *
 * <p>초과 시 {@code 429 Too Many Requests} + {@code Retry-After} 헤더.
 */
/**
 * {@link RateLimitConfig} 의 FilterRegistrationBean 으로 등록된다.
 * WebMvcTest 슬라이스에선 자동 등록되지 않아 슬라이스 단위 테스트에 영향을 주지 않는다.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitRegistry registry;
    private final List<RateLimitPolicy> policies;

    public RateLimitFilter(RateLimitRegistry registry, List<RateLimitPolicy> policies) {
        this.registry = registry;
        this.policies = policies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // servletPath 는 서블릿 매핑에 따라 빈 문자열이 될 수 있음 — RequestURI 가 더 안정적.
        String path = request.getRequestURI();
        RateLimitPolicy matched = findMatching(path);
        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitKeySource source = new RateLimitKeySource(
                extractIp(request), extractActorEmail());
        String key = matched.keyExtractor().apply(source);
        Bucket bucket = registry.resolve(matched, key);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("Rate limit exceeded. policy={}, key={}, retryAfterSec={}",
                    matched.name(), key, waitSeconds);
            response.setStatus(429); // TOO_MANY_REQUESTS — Jakarta Servlet 6 은 상수 미제공
            response.setHeader("Retry-After", String.valueOf(waitSeconds));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format(
                    "{\"error\":\"rate limit exceeded\",\"policy\":\"%s\",\"retryAfter\":%d}",
                    matched.name(), waitSeconds));
        }
    }

    private RateLimitPolicy findMatching(String path) {
        for (RateLimitPolicy p : policies) {
            if (p.matches(path)) return p;
        }
        return null;
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private String extractActorEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            Object principal = auth.getPrincipal();
            return principal != null ? principal.toString() : null;
        }
        return null;
    }

    public static List<RateLimitPolicy> defaultPolicies() {
        return List.of(
                new RateLimitPolicy(
                        "login", "/auth/login",
                        RateLimitPolicy.byIp(), 5, Duration.ofMinutes(1)),
                new RateLimitPolicy(
                        "refund", "/payments",   // /payments/{id}/refund 등
                        RateLimitPolicy.byActorOrIp(), 10, Duration.ofMinutes(1)),
                new RateLimitPolicy(
                        "admin", "/admin",
                        RateLimitPolicy.byActorOrIp(), 30, Duration.ofMinutes(1)),
                // 기프트카드 코드 등록 — 코드 무차별 대입의 표적이다. 코드 공간이 80비트로 넓어도
                // 속도 제한이 없으면 봇은 계속 두드린다. 정상 사용자는 분당 몇 번 이상 등록하지 않는다.
                new RateLimitPolicy(
                        "giftcard-redeem", "/api/gift-cards/redeem",
                        RateLimitPolicy.byActorOrIp(), 5, Duration.ofMinutes(1))
        );
    }
}
