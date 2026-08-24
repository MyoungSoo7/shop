package github.lms.lemuel.operation.board.domain.exception;

/**
 * 행위 주체에게 그 조작을 할 권한이 없다.
 *
 * <p><b>읽기에는 쓰지 않는다.</b> 읽을 수 없는 대상은 403 이 아니라 404 로 답한다 — 403 은
 * "여기 뭔가 있다"를 알려 줘서 식별자 대입으로 존재를 훑게 만든다. 이 예외는 쓰기·수정·삭제처럼
 * <b>대상의 존재를 이미 아는 주체</b>가 하는 조작에만 쓴다.
 */
public class BoardAccessDeniedException extends RuntimeException {

    public BoardAccessDeniedException(String message) {
        super(message);
    }
}
