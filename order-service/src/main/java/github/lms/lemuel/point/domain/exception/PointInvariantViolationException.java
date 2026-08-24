package github.lms.lemuel.point.domain.exception;

/**
 * 포인트 원장 불변식 위반 (도메인 계층 — 매핑하지 않는다: 500 이 옳다).
 *
 * <p>{@code total = available + locked}, 잔고 3필드 음수 금지, 그리고 포인트에만 있는
 * "잔고 요약과 로트 상세의 일치"가 깨졌을 때 던진다.
 *
 * <p>이 예외가 나오면 <b>로직 버그</b>다 — 사용자 입력으로 도달할 수 있는 상태가 아니다.
 * 절대 catch 해서 넘어가지 마라. 잡아서 무시하는 순간 장부가 조용히 틀어진다.
 */
public class PointInvariantViolationException extends RuntimeException {

    public PointInvariantViolationException(String message) {
        super(message);
    }
}
