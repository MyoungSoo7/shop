package github.lms.lemuel.web.security;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 웹 어댑터 횡단 IDOR 방어 — 사용자 리소스 식별자를 <b>요청 파라미터가 아니라 JWT 주체</b>에서
 * 대조한다(가드레일: "셀러/사용자 리소스 식별자를 요청 파라미터로 신뢰 금지").
 *
 * <p>여러 도메인 컨트롤러(cart·order·review)가 {@code /users/{userId}/…} 또는 {@code /…/user/{userId}}
 * 형태로 사용자 리소스를 노출하므로, 소유권 대조 로직을 한 곳에 모은다. investment-service 의
 * {@code requireSelf} 와 동형이되, 커머스 운영 지원을 위해 <b>ADMIN·MANAGER 는 우회</b>한다
 * (세무·문서함 컨트롤러의 "ADMIN/MANAGER 는 소유권 검사 우회"와 동일 정책).
 */
public final class ResourceOwnership {

    private ResourceOwnership() {
    }

    /**
     * JWT 인증 주체에서 사용자 식별자(uid claim)를 추출한다. 미인증이거나 식별 불가(구 토큰 등)면 403.
     */
    public static long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }

    /** ADMIN 또는 MANAGER 권한 보유 여부. */
    public static boolean isAdminOrManager(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority();
            if ("ROLE_ADMIN".equals(role) || "ROLE_MANAGER".equals(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 현재 SecurityContext 의 인증 주체 기준으로 소유권을 대조한다(컨트롤러 진입점).
     *
     * <p>{@code Authentication} 을 컨트롤러 파라미터로 받지 않고 홀더에서 직접 읽는다 — 파라미터 주입은
     * 보안 필터(SecurityContextHolderAwareRequestFilter)에 의존해 슬라이스 테스트에서 취약하기 때문이다.
     * 이 저장소의 {@code UserController} 와 동일한 방식.
     */
    public static void requireSelfOrAdmin(Long requestedUserId) {
        requireSelfOrAdmin(requestedUserId, SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 요청한 사용자 리소스가 인증 주체 본인의 것인지 확인한다. 본인도 ADMIN/MANAGER 도 아니면 403.
     */
    public static void requireSelfOrAdmin(Long requestedUserId, Authentication authentication) {
        if (isAdminOrManager(authentication)) {
            return;
        }
        // callerUserId 는 미인증·구 토큰(uid 없음)을 403 으로 번역한다.
        if (requestedUserId == null || requestedUserId != callerUserId(authentication)) {
            throw new AccessDeniedException("본인 소유가 아닌 사용자 리소스입니다. userId=" + requestedUserId);
        }
    }
}
