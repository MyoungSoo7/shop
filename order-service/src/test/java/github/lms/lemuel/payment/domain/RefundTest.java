package github.lms.lemuel.payment.domain;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class RefundTest {

    @Test @DisplayName("request: 유효한 파라미터로 환불 요청 생성")
    void request_valid() {
        Refund refund = Refund.request(1L, new BigDecimal("5000"), "idem-key-1", "고객 변심");
        assertThat(refund.getPaymentId()).isEqualTo(1L);
        assertThat(refund.getAmount()).isEqualByComparingTo("5000");
        assertThat(refund.getIdempotencyKey()).isEqualTo("idem-key-1");
        assertThat(refund.getReason()).isEqualTo("고객 변심");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.REQUESTED);
        assertThat(refund.getRequestedAt()).isNotNull();
        assertThat(refund.isCompleted()).isFalse();
    }

    @Test @DisplayName("request: null paymentId이면 예외")
    void request_nullPaymentId() {
        assertThatThrownBy(() -> Refund.request(null, BigDecimal.TEN, "key", "이유"))
                .isInstanceOf(PaymentInvariantViolationException.class)
                .hasMessage("paymentId required");
    }

    @Test @DisplayName("request: 0 이하 금액이면 예외")
    void request_zeroAmount() {
        assertThatThrownBy(() -> Refund.request(1L, BigDecimal.ZERO, "key", "이유"))
                .isInstanceOf(PaymentInvariantViolationException.class)
                .hasMessage("amount must be > 0");
    }

    @Test @DisplayName("request: null 금액이면 예외")
    void request_nullAmount() {
        assertThatThrownBy(() -> Refund.request(1L, null, "key", "이유"))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test @DisplayName("request: blank idempotencyKey이면 예외")
    void request_blankKey() {
        assertThatThrownBy(() -> Refund.request(1L, BigDecimal.TEN, "  ", "이유"))
                .isInstanceOf(PaymentInvariantViolationException.class)
                .hasMessage("idempotencyKey required");
    }

    @Test @DisplayName("markCompleted: REQUESTED → COMPLETED")
    void markCompleted() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markCompleted();
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.COMPLETED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(refund.isCompleted()).isTrue();
    }

    @Test @DisplayName("markCompleted: COMPLETED 상태에서 다시 호출하면 예외")
    void markCompleted_alreadyCompleted() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markCompleted();
        assertThatThrownBy(refund::markCompleted)
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("already COMPLETED");
    }

    @Test @DisplayName("markCompleted: 재시도로 살아난 FAILED → COMPLETED 전이 가능 + 재시도 예약 해제")
    void markCompleted_fromFailed() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markFailed("PG 일시 오류");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.FAILED);
        assertThat(refund.getNextRetryAt()).isNotNull();

        refund.markCompleted();
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.COMPLETED);
        assertThat(refund.isCompleted()).isTrue();
        assertThat(refund.getNextRetryAt()).isNull();
    }

    @Test @DisplayName("markFailed: REQUESTED → FAILED + 재시도 횟수 1 + 다음 재시도 예약")
    void markFailed() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markFailed("PG 오류");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.FAILED);
        assertThat(refund.getReason()).isEqualTo("PG 오류");
        assertThat(refund.getRetryCount()).isEqualTo(1);
        assertThat(refund.getNextRetryAt()).isNotNull();
        assertThat(refund.isRetryable()).isTrue();
        assertThat(refund.isRetryExhausted()).isFalse();
    }

    @Test @DisplayName("markFailed: 재시도 실패가 누적되면 상한 도달 시 재시도 소진(nextRetryAt=null)")
    void markFailed_exhaustsAfterMaxRetries() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        for (int i = 0; i < Refund.MAX_RETRIES; i++) {
            refund.markFailed("PG 오류 #" + i);
        }
        assertThat(refund.getRetryCount()).isEqualTo(Refund.MAX_RETRIES);
        assertThat(refund.getNextRetryAt()).isNull();
        assertThat(refund.isRetryable()).isFalse();
        assertThat(refund.isRetryExhausted()).isTrue();
    }

    @Test @DisplayName("markFailed: COMPLETED 상태에서 호출하면 예외")
    void markFailed_fromCompleted() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markCompleted();
        assertThatThrownBy(() -> refund.markFailed("실패"))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test @DisplayName("abandon: 적용 불가 실패건을 재시도 소진 상태로 고정")
    void abandon() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markFailed("PG 오류");
        refund.abandon("payment already fully refunded");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.FAILED);
        assertThat(refund.getReason()).isEqualTo("payment already fully refunded");
        assertThat(refund.getRetryCount()).isEqualTo(Refund.MAX_RETRIES);
        assertThat(refund.getNextRetryAt()).isNull();
        assertThat(refund.isRetryable()).isFalse();
        assertThat(refund.isRetryExhausted()).isTrue();
    }

    @Test @DisplayName("Status enum: 3개의 값")
    void statusEnum() {
        assertThat(Refund.Status.values()).containsExactly(
                Refund.Status.REQUESTED, Refund.Status.COMPLETED, Refund.Status.FAILED);
    }
}
