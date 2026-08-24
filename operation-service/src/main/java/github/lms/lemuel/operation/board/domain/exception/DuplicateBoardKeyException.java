package github.lms.lemuel.operation.board.domain.exception;

/**
 * 이미 쓰이고 있는 게시판 키.
 *
 * <p>키는 URL 세그먼트이자 메뉴 행이 가리키는 값이라 유일해야 한다. DB UNIQUE 제약이 최종
 * 방어선이지만, 그것만 두면 사용자는 원인을 알 수 없는 500 을 본다 — 응용 계층이 먼저 잡아
 * 409 로 돌려준다.
 */
public class DuplicateBoardKeyException extends RuntimeException {

    public DuplicateBoardKeyException(String boardKey) {
        super("이미 사용 중인 게시판 키입니다: " + boardKey);
    }
}
