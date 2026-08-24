package github.lms.lemuel.operation.notification.domain;

/**
 * 알림의 업무 분류 — <b>전달 채널이 아니라</b> 어떤 도메인 이벤트를 알리는가다.
 *
 * <p>enum 이라 제목/본문 매핑이 컴파일 시점에 전수(exhaustive)로 강제된다.
 */
public enum NotificationType {
    SETTLEMENT_CONFIRMED,
    PAYMENT_CONFIRMED,
    INVESTMENT_EXECUTED,
    GENERIC
}
