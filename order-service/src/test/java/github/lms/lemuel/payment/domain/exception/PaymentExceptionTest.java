package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PaymentExceptionTest {

    @Test @DisplayName("InvalidOrderStateForPaymentException: 메시지가 올바르게 전달된다")
    void invalidOrderStateException_message() {
        var ex = new InvalidOrderStateForPaymentException("주문 상태가 유효하지 않습니다");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("주문 상태가 유효하지 않습니다");
    }

    @Test @DisplayName("InvalidPaymentStateException: 상태 쌍 생성자")
    void invalidPaymentStateException_statusPair() {
        var ex = new InvalidPaymentStateException(PaymentStatus.READY, PaymentStatus.AUTHORIZED);
        assertThat(ex.getMessage()).isEqualTo("Invalid payment state. Current: READY, Required: AUTHORIZED");
    }

    @Test @DisplayName("InvalidPaymentStateException: 문자열 생성자")
    void invalidPaymentStateException_stringMessage() {
        var ex = new InvalidPaymentStateException("커스텀 메시지");
        assertThat(ex.getMessage()).isEqualTo("커스텀 메시지");
    }

    @Test @DisplayName("InvalidPaymentStateException: 다양한 상태 조합")
    void invalidPaymentStateException_variousStatuses() {
        var ex = new InvalidPaymentStateException(PaymentStatus.CAPTURED, PaymentStatus.REFUNDED);
        assertThat(ex.getMessage()).contains("CAPTURED").contains("REFUNDED");
    }

    @Test @DisplayName("OrderNotFoundException: 주문 ID가 메시지에 포함된다")
    void orderNotFoundException_message() {
        var ex = new OrderNotFoundException(42L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Order not found: 42");
    }

    @Test @DisplayName("PaymentNotFoundException: 결제 ID가 메시지에 포함된다")
    void paymentNotFoundException_message() {
        var ex = new PaymentNotFoundException(99L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Payment not found: 99");
    }
}
