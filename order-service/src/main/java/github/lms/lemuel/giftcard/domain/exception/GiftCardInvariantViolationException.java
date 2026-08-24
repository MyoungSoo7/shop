package github.lms.lemuel.giftcard.domain.exception;

/**
 * 기프트카드 불변식 위반 (도메인 계층 — 매핑하지 않는다: 500 이 옳다).
 *
 * <p>{@code 0 ≤ remaining ≤ face}, 귀속 상태와 소유자의 정합이 깨졌을 때 던진다.
 * 사용자 입력으로 도달할 수 있는 상태가 아니므로 <b>로직 버그</b>다 — catch 해서 넘어가지 마라.
 */
public class GiftCardInvariantViolationException extends RuntimeException {

    public GiftCardInvariantViolationException(String message) {
        super(message);
    }
}
