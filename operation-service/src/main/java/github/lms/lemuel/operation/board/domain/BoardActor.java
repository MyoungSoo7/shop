package github.lms.lemuel.operation.board.domain;

/**
 * 행위 주체 — 이 요청을 누가 어떤 역할로 보냈는가.
 *
 * <p><b>두 값 모두 JWT 에서만 온다.</b> 요청 본문·쿼리의 작성자 식별자는 절대 신뢰하지 않는다 —
 * 그 순간 남의 글을 자기 글이라 주장할 수 있는 IDOR 경로가 열린다.
 *
 * <p>미인증도 정상 주체다({@link #anonymous()}). 공개 게시판은 비로그인 방문자가 읽으므로,
 * "인증 안 됨"을 예외로 다루면 읽기 경로 전체가 특수 케이스로 오염된다.
 */
public record BoardActor(Long userId, String role) {

    private static final BoardActor ANONYMOUS = new BoardActor(null, null);

    public static BoardActor anonymous() {
        return ANONYMOUS;
    }

    public static BoardActor of(Long userId, String role) {
        return new BoardActor(userId, role);
    }

    public boolean isAuthenticated() {
        return userId != null;
    }

    /** 이 주체가 그 글의 작성자인가. 미인증은 언제나 거짓이다(null == null 로 통과시키지 않는다). */
    public boolean owns(Long authorId) {
        return userId != null && userId.equals(authorId);
    }
}
