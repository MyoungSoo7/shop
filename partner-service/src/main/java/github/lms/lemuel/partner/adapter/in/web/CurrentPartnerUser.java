package github.lms.lemuel.partner.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 조회 주체를 JWT 에서만 만든다.
 *
 * <p><b>이 클래스에는 요청을 인자로 받는 메서드가 없다.</b> 있으면 언젠가 누군가
 * {@code ?organizationId=} 를 읽게 되고, 그 순간 이 서비스는 아무나 남의 회사 매출을 조회하는
 * API 가 된다. 파트너 백오피스에서 이건 흔한 실수가 아니라 <i>기본값</i>에 가깝다 —
 * 화면이 이미 자기 조직 번호를 알고 있어서 그걸 그냥 보내는 게 자연스러워 보이기 때문이다.
 *
 * <p>그래서 흐름이 한 방향으로만 고정돼 있다: 토큰의 subject(userId) → 활성 멤버십 조회 →
 * 그 멤버십이 가리키는 조직 → 그 조직의 sellerId. 요청이 개입할 수 있는 지점이 없다.
 */
final class CurrentPartnerUser {

    private CurrentPartnerUser() {
    }

    /**
     * 로그인 사용자 id.
     *
     * <p>{@code userId} 가 없는 구 토큰은 거절한다. 그 토큰으로는 어느 조직 소속인지 알 수 없고,
     * 모르는 채로 조회를 진행하면 결국 "아무 조직" 이 되어 버린다.
     */
    static long requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("로그인이 필요합니다.");
        }
        if (!(authentication.getPrincipal() instanceof AuthPrincipal principal) || principal.userId() == null) {
            throw new AccessDeniedException("사용자를 특정할 수 없는 토큰입니다. 다시 로그인해 주세요.");
        }
        return principal.userId();
    }
}
