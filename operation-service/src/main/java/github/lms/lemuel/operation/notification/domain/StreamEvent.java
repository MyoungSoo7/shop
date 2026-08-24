package github.lms.lemuel.operation.notification.domain;

import java.time.Instant;

/**
 * 푸시 스트림 위의 알림 1건.
 *
 * <p>{@code seq} 는 클라이언트가 보는 재개 지점(SSE {@code id:} / {@code Last-Event-ID})이다.
 * 스트림이 부여하며 <b>수신자별이 아니라 전역 단조증가</b>다 — 한 구독자가 여러 신원(userId·이메일·
 * ops 메일함)으로 동시에 듣기 때문에, 재개에 쓸 id 축은 하나여야 한다.
 *
 * <p>순수 값 객체 — 프레임워크·I/O 의존 0.
 */
public record StreamEvent(
        long seq,
        Notification notification,
        Instant occurredAt
) {

    public StreamEvent {
        if (seq < 1) {
            throw new NotificationInvariantViolationException("seq must be >= 1, was " + seq);
        }
        if (notification == null) {
            throw new NotificationInvariantViolationException("notification must not be null");
        }
        if (occurredAt == null) {
            throw new NotificationInvariantViolationException("occurredAt must not be null");
        }
    }

    /** 이 이벤트의 수신 대상 — 푸시 허브의 라우팅 키. */
    public String recipient() {
        return notification.recipient();
    }
}
