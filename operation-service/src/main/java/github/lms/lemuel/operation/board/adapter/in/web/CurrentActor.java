package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 요청 주체를 JWT 에서만 만든다.
 *
 * <p><b>요청 본문·쿼리의 작성자 식별자는 읽지 않는다.</b> 한 번이라도 읽으면 남의 이름으로 글을
 * 쓸 수 있는 경로가 열린다(IDOR). 그래서 이 클래스에는 요청을 인자로 받는 메서드가 없다.
 */
final class CurrentActor {

    private CurrentActor() {
    }

    /** 읽기 경로용 — 미인증도 정상 주체다(공개 게시판은 비로그인이 읽는다). */
    static BoardActor resolve() {
        AuthPrincipal principal = principal();
        if (principal == null) {
            String role = CurrentRole.resolve();
            // 구 토큰(uid claim 없음)이나 principal 이 문자열인 경우 — 역할만 아는 주체로 다룬다.
            return role == null ? BoardActor.anonymous() : BoardActor.of(null, role);
        }
        return BoardActor.of(principal.userId(), principal.role());
    }

    /**
     * 쓰기 경로용 — 식별자가 있어야 작성자를 특정할 수 있다.
     *
     * <p>식별자 없는 토큰(구 토큰)으로 글을 쓰면 나중에 그 글의 주인을 찾을 수 없다. 소유권을
     * 세울 수 없는 글은 아예 만들지 않는다.
     */
    static BoardAuthor requireAuthor() {
        AuthPrincipal principal = principal();
        if (principal == null || principal.userId() == null) {
            throw new BoardAccessDeniedException("작성자를 특정할 수 없는 토큰입니다. 다시 로그인해 주세요.");
        }
        return BoardAuthor.fromSubject(principal.userId(), principal.email());
    }

    private static AuthPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AuthPrincipal authPrincipal ? authPrincipal : null;
    }
}
