package github.lms.lemuel.web.security;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 웹 어댑터 IDOR 방어 헬퍼 검증 — 사용자 리소스 소유권 대조.
 */
class ResourceOwnershipTest {

    private static Authentication auth(Long uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, uid + "@x.com", role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    @DisplayName("본인 리소스 요청은 통과한다")
    void selfAllowed() {
        assertThatCode(() -> ResourceOwnership.requireSelfOrAdmin(7L, auth(7L, "USER")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("타인 리소스 요청은 403 (IDOR 차단)")
    void otherDenied() {
        assertThatThrownBy(() -> ResourceOwnership.requireSelfOrAdmin(999L, auth(7L, "USER")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("ADMIN 은 타인 리소스도 우회 허용")
    void adminBypass() {
        assertThatCode(() -> ResourceOwnership.requireSelfOrAdmin(999L, auth(1L, "ADMIN")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MANAGER 는 타인 리소스도 우회 허용")
    void managerBypass() {
        assertThatCode(() -> ResourceOwnership.requireSelfOrAdmin(999L, auth(1L, "MANAGER")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("미인증(null)이면 403")
    void unauthenticatedDenied() {
        assertThatThrownBy(() -> ResourceOwnership.requireSelfOrAdmin(7L, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("구 토큰(uid claim 없음)은 식별 불가로 403")
    void legacyTokenDenied() {
        assertThatThrownBy(() -> ResourceOwnership.requireSelfOrAdmin(7L, auth(null, "USER")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("callerUserId: 정상 주체는 uid 반환, 미인증은 403")
    void callerUserIdResolution() {
        assertThat(ResourceOwnership.callerUserId(auth(42L, "USER"))).isEqualTo(42L);
        assertThatThrownBy(() -> ResourceOwnership.callerUserId(null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
