package github.lms.lemuel.marketing.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 참여 주체를 JWT 에서만 만든다.
 *
 * <p><b>요청 본문·쿼리의 회원 식별자는 읽지 않는다.</b> 레거시는 화면이 보낸 회원번호를 그대로
 * 믿었고, 그래서 요청 한 줄만 고치면 남의 회원번호로 출석을 찍고 당첨 포인트를 그 사람 계정에
 * 넣을 수 있었다. 그 경로를 원천 차단하려고 이 클래스에는 요청을 인자로 받는 메서드가 없다.
 */
final class CurrentMember {

    private CurrentMember() {
    }

    /**
     * 참여용 주체 — 숫자 회원 id 문자열.
     *
     * <p>문자열인 이유는 마케팅 도메인이 회원 식별자의 타입을 몰라도 되기 때문이다. 다만 값 자체는
     * order-service 의 회원 id 여야 한다 — 포인트 지급 요청을 만들 때 그 id 로 계정을 찾는다.
     *
     * <p>{@code userId} 가 없는 구 토큰은 거절한다. 그 토큰으로 출석을 찍으면 나중에 그 출석이
     * 누구 것인지 알 수 없고, 보상은 영원히 지급되지 않는다.
     */
    static String require() {
        AuthPrincipal principal = principal();
        if (principal == null || principal.userId() == null) {
            throw new AccessDeniedException("참여자를 특정할 수 없는 토큰입니다. 다시 로그인해 주세요.");
        }
        return String.valueOf(principal.userId());
    }

    private static AuthPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AuthPrincipal authPrincipal ? authPrincipal : null;
    }

    /** 감사 로그에 남길 운영자 이름. 관리 API 전용. */
    static String actor() {
        AuthPrincipal principal = principal();
        if (principal == null) {
            return "unknown";
        }
        return principal.email() == null ? String.valueOf(principal.userId()) : principal.email();
    }
}
