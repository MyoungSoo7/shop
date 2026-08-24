package github.lms.lemuel.operation.notification.domain;

/**
 * 알림 도메인 불변식 위반(공백 수신자·제목 등) 전용 예외 — 도메인은 맨 stdlib 예외를 던지지 않는다.
 *
 * <p>{@link IllegalArgumentException} 을 확장하는 이유는 둘이다.
 * <ol>
 *   <li>웹 경계의 400 매핑을 그대로 재사용한다.</li>
 *   <li>Kafka 에러 핸들러가 {@code IllegalArgumentException} 을 <b>재시도 불가(즉시 DLT)</b> 로
 *       분류한다 — 같은 바이트를 다시 파싱해도 같은 불변식에서 막히므로 재시도는 무의미하다.</li>
 * </ol>
 * 그러면서도 호출부·로그에서 임의의 IAE 와 구분된다(형제 서비스의 {@code *InvariantViolationException} 과 같은 관례).
 */
public class NotificationInvariantViolationException extends IllegalArgumentException {

    public NotificationInvariantViolationException(String message) {
        super(message);
    }
}
