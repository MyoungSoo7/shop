package github.lms.lemuel.payment.domain;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.RefundExceedsPaymentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class PaymentDomainTest {

    private PaymentDomain createReadyPayment() {
        return PaymentDomain.create(1L, new BigDecimal("10000"), "CARD");
    }

    private PaymentDomain createCapturedPayment() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx-123");
        p.capture();
        return p;
    }

    @Test @DisplayName("생성 시 READY 상태, 환불금액 0")
    void creation() {
        PaymentDomain p = createReadyPayment();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.getOrderId()).isEqualTo(1L);
        assertThat(p.getAmount()).isEqualByComparingTo("10000");
    }

    @Test @DisplayName("READY → AUTHORIZED 성공")
    void authorize_success() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx-123");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(p.getPgTransactionId()).isEqualTo("pg-tx-123");
    }

    @Test @DisplayName("AUTHORIZED가 아닌 상태에서 authorize 실패")
    void authorize_fail_wrongStatus() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-1");
        assertThatThrownBy(() -> p.authorize("pg-2"))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("AUTHORIZED → CAPTURED 성공")
    void capture_success() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx");
        p.capture();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getCapturedAt()).isNotNull();
    }

    @Test @DisplayName("READY에서 capture 실패")
    void capture_fail_notAuthorized() {
        PaymentDomain p = createReadyPayment();
        assertThatThrownBy(p::capture).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("AUTHORIZED → CANCELED (승인취소) 성공")
    void cancel_success() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx");
        p.cancel();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test @DisplayName("READY 상태에서 cancel 실패 (AUTHORIZED 만 취소 가능)")
    void cancel_fail_notAuthorized() {
        PaymentDomain p = createReadyPayment();
        assertThatThrownBy(p::cancel).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("CAPTURED 이후에는 cancel 불가 (refund 경로 사용)")
    void cancel_fail_afterCapture() {
        PaymentDomain p = createCapturedPayment();
        assertThatThrownBy(p::cancel).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("READY → EXPIRED (미입금 만료) 성공")
    void expire_success() {
        PaymentDomain p = PaymentDomain.create(1L, new BigDecimal("10000"), "VIRTUAL_ACCOUNT");
        p.expire();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test @DisplayName("승인된 결제는 만료 불가 (승인취소 경로 사용)")
    void expire_fail_afterAuthorize() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx");
        assertThatThrownBy(p::expire).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("매입된 결제는 만료 불가 (환불 경로 사용)")
    void expire_fail_afterCapture() {
        PaymentDomain p = createCapturedPayment();
        assertThatThrownBy(p::expire).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("만료는 종단 — 두 번째 만료는 차단된다")
    void expire_twice_blocked() {
        PaymentDomain p = PaymentDomain.create(1L, new BigDecimal("10000"), "VIRTUAL_ACCOUNT");
        p.expire();
        assertThatThrownBy(p::expire).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("CAPTURED → REFUNDED 성공")
    void refund_success() {
        PaymentDomain p = createCapturedPayment();
        p.refund();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test @DisplayName("READY에서 refund 실패")
    void refund_fail_notCaptured() {
        PaymentDomain p = createReadyPayment();
        assertThatThrownBy(p::refund).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("환불 가능 금액 계산")
    void refundableAmount() {
        PaymentDomain p = createCapturedPayment();
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("10000");
        p.addRefundedAmount(new BigDecimal("3000"));
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("7000");
    }

    @Test @DisplayName("전액 환불 여부")
    void fullyRefunded() {
        PaymentDomain p = createCapturedPayment();
        assertThat(p.isFullyRefunded()).isFalse();
        p.addRefundedAmount(new BigDecimal("10000"));
        assertThat(p.isFullyRefunded()).isTrue();
    }

    @Test @DisplayName("부분 환불 누적")
    void addRefundedAmount() {
        PaymentDomain p = createCapturedPayment();
        p.addRefundedAmount(new BigDecimal("2000"));
        p.addRefundedAmount(new BigDecimal("3000"));
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("5000");
    }

    @Test @DisplayName("정확히 전액까지는 환불 누적 허용")
    void addRefundedAmount_exactlyFull_ok() {
        PaymentDomain p = createCapturedPayment();
        p.addRefundedAmount(new BigDecimal("6000"));
        p.addRefundedAmount(new BigDecimal("4000")); // 누적 10000 == amount
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("10000");
        assertThat(p.isFullyRefunded()).isTrue();
    }

    @Test @DisplayName("누적 초과환불은 도메인이 차단(초과환불 불변식)")
    void addRefundedAmount_exceedsAmount_blockedByDomain() {
        PaymentDomain p = createCapturedPayment();
        p.addRefundedAmount(new BigDecimal("7000"));
        // 남은 환불 가능액은 3000 인데 4000 을 누적하면 amount(10000) 초과 → 도메인 차단
        assertThatThrownBy(() -> p.addRefundedAmount(new BigDecimal("4000")))
                .isInstanceOf(RefundExceedsPaymentException.class);
        // 위반 시 상태 불변: 누적액은 갱신되지 않는다
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("7000");
    }

    @Test @DisplayName("첫 환불부터 amount 초과 시 도메인 차단")
    void addRefundedAmount_singleExceeds_blockedByDomain() {
        PaymentDomain p = createCapturedPayment();
        assertThatThrownBy(() -> p.addRefundedAmount(new BigDecimal("10001")))
                .isInstanceOf(RefundExceedsPaymentException.class);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test @DisplayName("전체 생명주기: READY → AUTHORIZED → CAPTURED → REFUNDED")
    void fullLifecycle() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-123");
        p.capture();
        p.refund();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test @DisplayName("복원 생성자")
    void reconstitution() {
        PaymentDomain p = PaymentDomain.rehydrate(1L, 2L, new BigDecimal("5000"), new BigDecimal("1000"),
                PaymentStatus.CAPTURED, "CARD", "pg-tx", null, null, null);
        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("1000");
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("4000");
    }
}
