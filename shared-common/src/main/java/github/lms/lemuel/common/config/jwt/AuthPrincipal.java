package github.lms.lemuel.common.config.jwt;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * SecurityContext 의 {@code authentication.getPrincipal()} 로 노출되는 인증 주체.
 *
 * <p>{@link AuthenticatedPrincipal#getName()} 과 {@link #toString()} 모두 email 을 반환하여,
 * 기존에 principal 을 email 문자열로 다루던 코드와 100% 하위호환된다:
 * <ul>
 *   <li>{@code authentication.getName()} → AbstractAuthenticationToken 이 AuthenticatedPrincipal#getName() 사용 → email</li>
 *   <li>{@code principal.toString()} (RateLimitFilter, AuditContextFilter) → email</li>
 * </ul>
 *
 * <p>추가로 {@code userId()} 로 식별자를, {@code role()} 로 역할을 코드에서 직접 사용할 수 있다.
 * userId 는 구(舊) 토큰(uid claim 없음)에서는 null 일 수 있다.
 */
public record AuthPrincipal(Long userId, String email, String role) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return email;
    }

    @Override
    public String toString() {
        return email;
    }
}
