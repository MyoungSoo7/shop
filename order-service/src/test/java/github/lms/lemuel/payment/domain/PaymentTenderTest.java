package github.lms.lemuel.payment.domain;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTenderTest {

    @Test
    @DisplayName("newTender: PENDING 상태 + 환불 0 으로 시작")
    void create() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("10000"), 1);

        assertThat(t.getType()).isEqualTo(TenderType.CARD);
        assertThat(t.getStatus()).isEqualTo(TenderStatus.PENDING);
        assertThat(t.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.getRefundableAmount()).isEqualByComparingTo("10000");
        assertThat(t.getSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("authorize: 외부 PG tender 는 pgTransactionId 필수")
    void authorize_externalPg_requiresTxnId() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 1);

        assertThatThrownBy(() -> t.authorize(null)).isInstanceOf(PaymentInvariantViolationException.class);
        assertThatThrownBy(() -> t.authorize(""))  .isInstanceOf(PaymentInvariantViolationException.class);

        t.authorize("TOSS:abc-123");
        assertThat(t.getStatus()).isEqualTo(TenderStatus.AUTHORIZED);
        assertThat(t.getPgTransactionId()).isEqualTo("TOSS:abc-123");
    }

    @Test
    @DisplayName("authorize: 내부 잔액 (POINT/GIFT_CARD) 은 pgTransactionId null 허용")
    void authorize_internal_allowsNullTxnId() {
        PaymentTender p = PaymentTender.newTender(TenderType.POINT, new BigDecimal("500"), 1);
        p.authorize(null);
        assertThat(p.getStatus()).isEqualTo(TenderStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("addRefund: 부분 환불 시 refundedAmount 누적, 전액 도달 시 REFUNDED 전이")
    void partialRefund_thenFullRefund() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("10000"), 1);
        t.authorize("TX-1"); t.capture();

        t.addRefund(new BigDecimal("3000"));
        assertThat(t.getStatus()).isEqualTo(TenderStatus.CAPTURED);
        assertThat(t.getRefundedAmount()).isEqualByComparingTo("3000");
        assertThat(t.getRefundableAmount()).isEqualByComparingTo("7000");

        t.addRefund(new BigDecimal("7000"));
        assertThat(t.getStatus()).isEqualTo(TenderStatus.REFUNDED);
        assertThat(t.isFullyRefunded()).isTrue();
    }

    @Test
    @DisplayName("addRefund: 잔여 환불액 초과 시 IllegalArgumentException")
    void refund_exceedsRefundable() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 1);
        t.authorize("TX-1"); t.capture();

        assertThatThrownBy(() -> t.addRefund(new BigDecimal("1500")))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test
    @DisplayName("addRefund: CAPTURED 가 아닌 상태에서 거부")
    void refund_wrongState() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 1);
        // PENDING
        assertThatThrownBy(() -> t.addRefund(new BigDecimal("100")))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("validation: amount 음수 / sequence 0 거부")
    void validation() {
        assertThatThrownBy(() -> PaymentTender.newTender(TenderType.CARD, BigDecimal.ZERO, 1))
                .isInstanceOf(PaymentInvariantViolationException.class);
        assertThatThrownBy(() -> PaymentTender.newTender(TenderType.CARD, new BigDecimal("100"), 0))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }
}
