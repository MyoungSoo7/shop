package github.lms.lemuel.operation.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 알림 템플릿(순수 도메인) 규칙 — 토픽 분류표·수신자 파생·금액 필드 폴백.
 *
 * <p>ADR 0039 후속: 폴리글랏 notification-service(Kotlin)에서 이관된 케이스를 그대로 보존한다.
 * 이관 전 검증하던 계약이 하나라도 빠지면 "옮기면서 조용히 약해진" 것이므로 원본 케이스를
 * 1:1 로 옮기고, Java 포팅에서 새로 생긴 경계(레코드 불변식)만 추가한다.
 */
class NotificationTemplateTest {

    @Test
    @DisplayName("토픽을 알림 유형으로 분류한다")
    void classifiesTopicsIntoNotificationTypes() {
        assertEquals(NotificationType.SETTLEMENT_CONFIRMED,
                NotificationTemplate.classify("lemuel.settlement.confirmed"));
        assertEquals(NotificationType.PAYMENT_CONFIRMED,
                NotificationTemplate.classify("lemuel.payment.confirmed"));
        assertEquals(NotificationType.PAYMENT_CONFIRMED,
                NotificationTemplate.classify("lemuel.payment.captured"));
        assertEquals(NotificationType.PAYMENT_CONFIRMED,
                NotificationTemplate.classify("lemuel.payment.refunded"));
        assertEquals(NotificationType.INVESTMENT_EXECUTED,
                NotificationTemplate.classify("lemuel.investment.executed"));
        assertEquals(NotificationType.GENERIC,
                NotificationTemplate.classify("some.random.topic"));
    }

    @Test
    @DisplayName("정확일치 키(언더스코어 표기)도 분류표가 받는다")
    void classifiesExactUnderscoreKeys() {
        assertEquals(NotificationType.SETTLEMENT_CONFIRMED, NotificationTemplate.classify("settlement_confirmed"));
        assertEquals(NotificationType.PAYMENT_CONFIRMED, NotificationTemplate.classify("payment_confirmed"));
        assertEquals(NotificationType.INVESTMENT_EXECUTED, NotificationTemplate.classify("investment_executed"));
    }

    @Test
    @DisplayName("정산 이벤트 필드로 알림을 만든다")
    void buildsNotificationFromSettlementEventFields() {
        Notification n = NotificationTemplate.fromEvent(
                "lemuel.settlement.confirmed",
                Map.of("settlementId", "STL-42", "recipient", "a@b.c", "amount", 1000),
                "evt-1");

        assertEquals(NotificationType.SETTLEMENT_CONFIRMED, n.type());
        assertEquals("a@b.c", n.recipient());
        assertEquals("evt-1", n.eventId());
        assertTrue(n.subject().contains("STL-42"));
        assertTrue(n.body().contains("1000"));
    }

    @Test
    @DisplayName("주소 필드가 없으면 ops 폴백 수신자로 간다")
    void fallsBackToOpsRecipientWhenNonePresent() {
        Notification n = NotificationTemplate.fromEvent("lemuel.payment.confirmed", Map.of(), null);
        assertEquals(NotificationTemplate.OPS_FALLBACK_RECIPIENT, n.recipient());
    }

    @Test
    @DisplayName("정본 Outbox 이벤트는 sellerId 로 셀러를 지목한다")
    void canonicalOutboxEventsAddressTheSellerViaSellerId() {
        Notification n = NotificationTemplate.fromEvent(
                "lemuel.settlement.confirmed",
                Map.of("settlementId", "STL-7", "sellerId", 1001, "amount", "50000"),
                "evt-2");

        assertEquals("1001", n.recipient());
    }

    @Test
    @DisplayName("수신자 우선순위: recipient > sellerId > userId > accountId")
    void recipientPrecedenceIsStable() {
        Notification n = NotificationTemplate.fromEvent(
                "lemuel.settlement.confirmed",
                Map.of("recipient", "first@x", "sellerId", 1, "userId", 2, "accountId", 3),
                null);
        assertEquals("first@x", n.recipient());

        Notification bySeller = NotificationTemplate.fromEvent(
                "lemuel.settlement.confirmed", Map.of("sellerId", 1, "userId", 2, "accountId", 3), null);
        assertEquals("1", bySeller.recipient());

        Notification byUser = NotificationTemplate.fromEvent(
                "lemuel.settlement.confirmed", Map.of("userId", 2, "accountId", 3), null);
        assertEquals("2", byUser.recipient());

        Notification byAccount = NotificationTemplate.fromEvent(
                "lemuel.settlement.confirmed", Map.of("accountId", 3), null);
        assertEquals("3", byAccount.recipient());
    }

    @Test
    @DisplayName("환불 이벤트 금액은 refundAmount 에서 온다")
    void refundedEventAmountComesFromRefundAmount() {
        Notification n = NotificationTemplate.fromEvent(
                "lemuel.payment.refunded",
                Map.of("paymentId", "PAY-1", "sellerId", 1001, "refundAmount", "12000"),
                "evt-3");

        assertTrue(n.body().contains("12000"));
    }

    @Test
    @DisplayName("Go 웹훅 payment.confirmed 는 paymentKey·totalAmount 를 쓴다")
    void goWebhookPaymentConfirmedUsesPaymentKeyAndTotalAmount() {
        Notification n = NotificationTemplate.fromEvent(
                "lemuel.payment.confirmed",
                Map.of("paymentKey", "tosskey-9", "totalAmount", 33000),
                "evt-4");

        assertTrue(n.subject().contains("tosskey-9"));
        assertTrue(n.body().contains("33000"));
    }

    @Test
    @DisplayName("금액 필드가 하나도 없으면 금액 문구를 붙이지 않는다")
    void omitsAmountClauseWhenNoAmountField() {
        Notification n = NotificationTemplate.fromEvent(
                "lemuel.investment.executed", Map.of("orderId", "ORD-3"), null);

        assertTrue(n.subject().contains("ORD-3"));
        assertTrue(n.body().endsWith("처리되었습니다."), "금액 없는 본문: " + n.body());
    }

    @Test
    @DisplayName("식별자가 없으면 물음표로 렌더한다(누락을 숨기지 않는다)")
    void rendersQuestionMarkWhenIdentifierMissing() {
        assertTrue(NotificationTemplate.fromEvent("lemuel.settlement.confirmed", Map.of(), null)
                .subject().contains("?"));
        assertTrue(NotificationTemplate.fromEvent("lemuel.investment.executed", Map.of(), null)
                .subject().contains("?"));
    }

    @Test
    @DisplayName("GENERIC 은 토픽명을 제목에 그대로 싣는다")
    void genericSubjectCarriesTopicName() {
        Notification n = NotificationTemplate.fromEvent("some.random.topic", Map.of(), null);
        assertEquals(NotificationType.GENERIC, n.type());
        assertTrue(n.subject().contains("some.random.topic"));
    }

    @Test
    @DisplayName("renderPlainText 는 결정적이고 핵심 필드를 포함한다")
    void renderPlainTextIsDeterministicAndIncludesKeyFields() {
        Notification n = new Notification(NotificationType.GENERIC, "x@y.z", "Sub", "Bod", null);
        assertEquals("[GENERIC] to=x@y.z :: Sub — Bod", NotificationTemplate.renderPlainText(n));
    }

    @Test
    @DisplayName("공백 수신자·제목은 도메인 예외로 차단된다")
    void blankRecipientOrSubjectIsRejected() {
        assertThrows(NotificationInvariantViolationException.class,
                () -> new Notification(NotificationType.GENERIC, "  ", "s", "b", null));
        assertThrows(NotificationInvariantViolationException.class,
                () -> new Notification(NotificationType.GENERIC, "r", " ", "b", null));
    }
}
