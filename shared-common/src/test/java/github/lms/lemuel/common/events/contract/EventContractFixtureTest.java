package github.lms.lemuel.common.events.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계약 픽스처 자체 무결성 검증 — 정본 샘플이 스키마와 어긋나면 계약 테스트 전체가 무의미해지므로,
 * 스키마·샘플 변경 시 이 테스트가 먼저 깨져야 한다 (ADR 0024).
 */
class EventContractFixtureTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "lemuel.payment.captured",
            "lemuel.payment.refunded",
            "lemuel.settlement.created",
            "lemuel.settlement.confirmed",
            "lemuel.payout.completed",
            "lemuel.loan.repayment_applied",
            "lemuel.loan.disbursement_requested",
            "lemuel.loan.corporate_loan_disbursed",
            "lemuel.company.reputation_changed",
            "lemuel.order.created",
            "lemuel.user.registered",
            "lemuel.product.changed",
            "lemuel.investment.executed",
            "lemuel.organization.created",
            "lemuel.organization.member_joined",
            "lemuel.organization.member_role_changed",
            "lemuel.organization.member_removed",
            "lemuel.settlement.holdback_released",
            "lemuel.settlement.holdback_consumed",
            "lemuel.settlement.adjusted",
            "lemuel.settlement.canceled",
            "lemuel.seller_recovery.opened",
            "lemuel.seller_recovery.offset",
            "lemuel.settlement.withholding_accrued",
            "lemuel.card.account_opened",
            "lemuel.card.issued",
            "lemuel.card.limit_changed",
            "lemuel.card.status_changed",
            "lemuel.card.account_status_changed",
            "lemuel.card.authorized",
            "lemuel.card.captured"
    })
    @DisplayName("모든 토픽의 정본 샘플은 자기 계약 스키마를 통과한다")
    void canonicalSamples_areValidAgainstTheirSchemas(String topic) {
        EventContractValidator.assertValid(topic, EventContractValidator.canonicalSample(topic));
    }

    @Test
    @DisplayName("필수 필드 삭제(paymentId 없는 captured)는 계약 위반으로 검출된다")
    void missingRequiredField_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.captured",
                "{\"orderId\":5001,\"amount\":\"45000\"}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("타입 변경(amount 문자열→숫자)은 계약 위반으로 검출된다")
    void typeDrift_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.captured",
                "{\"paymentId\":1001,\"orderId\":5001,\"amount\":45000}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("금액 필드가 하나도 없는 refunded 는 계약 위반이다 (anyOf)")
    void refundedWithoutAnyAmount_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.refunded",
                "{\"paymentId\":1001,\"orderId\":5001,\"refundId\":42}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("optional 필드 추가는 계약 위반이 아니다 (additionalProperties 허용 — 전방 호환)")
    void additiveField_isNotViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.settlement.confirmed",
                "{\"settlementId\":9001,\"sellerId\":777,\"amount\":\"43425\",\"newOptionalField\":\"x\"}");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("등록되지 않은 토픽은 명확한 예외를 던진다")
    void unknownTopic_throws() {
        assertThatThrownBy(() -> EventContractValidator.validate("lemuel.unknown.topic", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lemuel.unknown.topic");
    }
}
