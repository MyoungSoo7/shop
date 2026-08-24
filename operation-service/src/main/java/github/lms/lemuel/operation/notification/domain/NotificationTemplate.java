package github.lms.lemuel.operation.notification.domain;

import java.util.List;
import java.util.Map;

/**
 * 순수·테스트 가능한 포매팅. 원시 도메인 이벤트 페이로드를 발송 가능한 {@link Notification} 으로 바꾼다.
 * 부수효과 없음.
 */
public final class NotificationTemplate {

    /**
     * 이벤트에 주소로 쓸 필드가 하나도 없을 때의 최후 수신자 — 운영 메일함으로 보낸다.
     * 조용한 드랍이 아니라 <b>의도된 fail-visible 기본값</b>이다(아무도 모르게 사라지는 알림을 만들지 않는다).
     */
    public static final String OPS_FALLBACK_RECIPIENT = "ops@lemuel";

    /** 페이로드에서 수신자를 찾을 때의 우선순위. 앞에서 먼저 맞는 것이 이긴다. */
    private static final List<String> RECIPIENT_FIELDS = List.of("recipient", "sellerId", "userId", "accountId");

    /**
     * 금액 필드 폴백 순서. payment.refunded 는 refundAmount/refundedAmount 를,
     * Go payment-webhook 의 payment.confirmed 는 totalAmount 를 싣는다 — 어느 쪽도 맨 {@code amount} 가 아니다.
     */
    private static final List<String> AMOUNT_FIELDS = List.of("amount", "refundAmount", "refundedAmount", "totalAmount");

    /**
     * 토픽/타입 → 분류 매핑을 <b>선언적 규칙표</b>로 둔다(부분일치 OR 정확일치). 매핑 추가가
     * 새 분기 로직이 아니라 데이터 편집이 되도록 한 것이다. 먼저 맞는 규칙이 이기고,
     * 아무것도 안 맞으면 보수적으로 GENERIC.
     */
    private static final List<ClassificationRule> CLASSIFICATION_RULES = List.of(
            new ClassificationRule(NotificationType.SETTLEMENT_CONFIRMED,
                    List.of("settlement.confirmed"), List.of("settlement_confirmed")),
            new ClassificationRule(NotificationType.PAYMENT_CONFIRMED,
                    List.of("payment.confirmed", "payment.captured", "payment.refunded"),
                    List.of("payment_confirmed")),
            new ClassificationRule(NotificationType.INVESTMENT_EXECUTED,
                    List.of("investment.executed"), List.of("investment_executed")));

    private NotificationTemplate() {
    }

    /** LogChannel 이 쓰고 다른 채널의 본문 폴백이 되는 한 줄 평문 표현. */
    public static String renderPlainText(Notification n) {
        return "[%s] to=%s :: %s — %s".formatted(n.type(), n.recipient(), n.subject(), n.body());
    }

    /**
     * 디코딩된 도메인 이벤트로 알림을 만든다.
     *
     * @param topicOrType 논리 이벤트명(kafka 토픽 또는 명시 타입 문자열)
     * @param fields      디코딩된 이벤트 페이로드
     * @param eventId     멱등 키(없으면 null)
     */
    public static Notification fromEvent(String topicOrType, Map<String, Object> fields, String eventId) {
        NotificationType type = classify(topicOrType);
        String recipient = firstPresent(fields, RECIPIENT_FIELDS)
                .map(Object::toString)
                .orElse(OPS_FALLBACK_RECIPIENT);

        String subject = switch (type) {
            case SETTLEMENT_CONFIRMED -> "정산 확정: " + valueOrUnknown(fields, "settlementId");
            // Go payment-webhook 이벤트는 paymentId 대신 paymentKey 를 싣는다.
            case PAYMENT_CONFIRMED -> "결제 확인: " + firstPresent(fields, List.of("paymentId", "paymentKey"))
                    .map(Object::toString).orElse(UNKNOWN);
            case INVESTMENT_EXECUTED -> "투자 체결: " + valueOrUnknown(fields, "orderId");
            case GENERIC -> "알림: " + topicOrType;
        };

        String amount = firstPresent(fields, AMOUNT_FIELDS)
                .map(v -> " (금액 %s)".formatted(v))
                .orElse("");
        String body = "%s 이벤트가 처리되었습니다%s.".formatted(topicOrType, amount);

        return new Notification(type, recipient, subject, body, eventId);
    }

    public static NotificationType classify(String topicOrType) {
        String key = topicOrType == null ? "" : topicOrType.toLowerCase(java.util.Locale.ROOT);
        return CLASSIFICATION_RULES.stream()
                .filter(rule -> rule.matches(key))
                .map(ClassificationRule::type)
                .findFirst()
                .orElse(NotificationType.GENERIC);
    }

    private static final String UNKNOWN = "?";

    private static String valueOrUnknown(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        return value == null ? UNKNOWN : value.toString();
    }

    private static java.util.Optional<Object> firstPresent(Map<String, Object> fields, List<String> candidates) {
        return candidates.stream()
                .map(fields::get)
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    /** 분류표의 한 행: 유형 + 그 유형을 고르는 키들. */
    private record ClassificationRule(NotificationType type, List<String> substrings, List<String> exactKeys) {
        boolean matches(String key) {
            return substrings.stream().anyMatch(key::contains) || exactKeys.contains(key);
        }
    }
}
