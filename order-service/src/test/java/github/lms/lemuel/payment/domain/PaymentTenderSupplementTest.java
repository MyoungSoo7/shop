package github.lms.lemuel.payment.domain;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentTender — 재수화/capture/식별자/유형 보조 커버리지")
class PaymentTenderSupplementTest {

    @Test
    @DisplayName("capture — PENDING 에서 바로 CAPTURED 가능(내부 잔액)")
    void captureFromPending() {
        PaymentTender t = PaymentTender.newTender(TenderType.POINT, new BigDecimal("500"), 1);
        t.capture();
        assertThat(t.getStatus()).isEqualTo(TenderStatus.CAPTURED);
    }

    @Test
    @DisplayName("capture — REFUNDED 등 잘못된 상태 거부")
    void capture_wrongState() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 1);
        t.authorize("TX"); t.capture();
        t.addRefund(new BigDecimal("1000"));
        assertThat(t.getStatus()).isEqualTo(TenderStatus.REFUNDED);
        assertThatThrownBy(t::capture).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("authorize — PENDING 이 아니면 거부")
    void authorize_wrongState() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 1);
        t.authorize("TX");
        assertThatThrownBy(() -> t.authorize("TX2")).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("addRefund — 0/음수 금액 거부, isFullyRefunded 초기 false")
    void addRefund_invalid() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 1);
        t.authorize("TX"); t.capture();
        assertThat(t.isFullyRefunded()).isFalse();
        assertThatThrownBy(() -> t.addRefund(BigDecimal.ZERO)).isInstanceOf(PaymentInvariantViolationException.class);
        assertThatThrownBy(() -> t.addRefund(new BigDecimal("-1"))).isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test
    @DisplayName("attachToPayment / assignId — 식별자 부여, id 재부여 예외")
    void identifiers() {
        PaymentTender t = PaymentTender.newTender(TenderType.CARD, new BigDecimal("1000"), 2);
        t.attachToPayment(77L);
        assertThat(t.getPaymentId()).isEqualTo(77L);
        t.assignId(5L);
        assertThat(t.getId()).isEqualTo(5L);
        assertThatThrownBy(() -> t.assignId(6L)).isInstanceOf(IllegalStateException.class);
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("rehydrate — 영속 상태 복원")
    void rehydrate() {
        LocalDateTime now = LocalDateTime.now();
        PaymentTender t = PaymentTender.rehydrate(1L, 2L, TenderType.KAKAO_PAY, new BigDecimal("3000"),
                new BigDecimal("1000"), "PGTX", TenderStatus.CAPTURED, 3, now, now);
        assertThat(t.getId()).isEqualTo(1L);
        assertThat(t.getPaymentId()).isEqualTo(2L);
        assertThat(t.getType()).isEqualTo(TenderType.KAKAO_PAY);
        assertThat(t.getRefundedAmount()).isEqualByComparingTo("1000");
        assertThat(t.getRefundableAmount()).isEqualByComparingTo("2000");
        assertThat(t.getPgTransactionId()).isEqualTo("PGTX");
        assertThat(t.getSequence()).isEqualTo(3);
    }

    @Test
    @DisplayName("TenderType — usesExternalPg / isExternalFirst")
    void tenderTypeFlags() {
        assertThat(TenderType.CARD.usesExternalPg()).isTrue();
        assertThat(TenderType.CARD.isExternalFirst()).isTrue();
        assertThat(TenderType.POINT.usesExternalPg()).isFalse();
        assertThat(TenderType.GIFT_CARD.isExternalFirst()).isFalse();
    }
}
