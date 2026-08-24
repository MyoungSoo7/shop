package github.lms.lemuel.operation.board.domain.exception;

/**
 * 게시글이 없거나, 호출자에게는 없는 것과 같다(비밀글·숨김·삭제).
 *
 * <p>"없음"과 "볼 수 없음"을 같은 응답으로 합치는 것은 의도한 설계다 — 가르는 순간 식별자를
 * 훑어 비밀글의 존재를 알아낼 수 있다.
 */
public class BoardPostNotFoundException extends RuntimeException {

    public BoardPostNotFoundException(String message) {
        super(message);
    }

    public static BoardPostNotFoundException byId(Long id) {
        return new BoardPostNotFoundException("게시글을 찾을 수 없습니다: id=" + id);
    }
}
