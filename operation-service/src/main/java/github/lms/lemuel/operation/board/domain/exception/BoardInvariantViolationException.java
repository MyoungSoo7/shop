package github.lms.lemuel.operation.board.domain.exception;

/**
 * 게시판 도메인 불변식 위반.
 *
 * <p>게시판 정의는 게시글이 지켜야 할 규칙을 담는 그릇이다. 그릇 자체가 모순이면(예: 이미지
 * 게시판인데 첨부 불가) 그 위에 쌓이는 모든 글이 검증 불가능한 상태가 되므로, 조립 시점에 막는다.
 */
public class BoardInvariantViolationException extends RuntimeException {

    public BoardInvariantViolationException(String message) {
        super(message);
    }
}
