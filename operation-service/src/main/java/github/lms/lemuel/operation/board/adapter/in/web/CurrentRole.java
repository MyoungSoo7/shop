package github.lms.lemuel.operation.board.adapter.in.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 호출자의 역할 코드 한 개를 꺼낸다.
 *
 * <p>이 플랫폼의 JWT 는 역할을 <b>단일 문자열 클레임</b>({@code role})로 싣는다. 그래서
 * "권한 목록의 첫 값"이 곧 역할이고, 미인증·익명이면 {@code null} 이다 — 게시판의 공개 판정은
 * {@code null} 역할을 정상 입력으로 받아 처리한다(공개 게시판은 비로그인도 읽는다).
 */
final class CurrentRole {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ANONYMOUS_ROLE = "ANONYMOUS";

    private CurrentRole() {
    }

    static String resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith(ROLE_PREFIX)
                        ? authority.substring(ROLE_PREFIX.length())
                        : authority)
                .filter(role -> !ANONYMOUS_ROLE.equals(role))
                .findFirst()
                .orElse(null);
    }
}
