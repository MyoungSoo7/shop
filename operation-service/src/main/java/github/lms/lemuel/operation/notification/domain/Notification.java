package github.lms.lemuel.operation.notification.domain;

/**
 * 순수 도메인 값 객체 — 프레임워크·I/O 의존 0.
 *
 * <p>불변식은 compact constructor 에서 강제하므로 <b>모든 생성 경로</b>가 검증을 다시 탄다.
 * record 라 setter 가 존재할 수 없고, 유효하지 않은 인스턴스를 손에 넣을 방법이 없다.
 *
 * @param eventId 멱등 dedupe 키. {@code null} 이면 "이벤트에서 온 것이 아님"(예: 임시 REST 발송)이라
 *                dedupe 대상이 아니다 — 같은 내용을 두 번 보내는 것이 의도인 경로다.
 */
public record Notification(
        NotificationType type,
        String recipient,
        String subject,
        String body,
        String eventId
) {

    public Notification {
        if (recipient == null || recipient.isBlank()) {
            throw new NotificationInvariantViolationException("recipient must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationInvariantViolationException("subject must not be blank");
        }
    }

    /** 같은 알림에 이벤트 식별자만 입힌 사본 — 불변식을 다시 태운다. */
    public Notification withEventId(String newEventId) {
        return new Notification(type, recipient, subject, body, newEventId);
    }
}
