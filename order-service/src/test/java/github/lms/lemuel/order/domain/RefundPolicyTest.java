package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundPolicyTest {

    @Test @DisplayName("배송 시작 전: 전액 환불, 배송비 차감 없음")
    void beforeShipping_fullRefund() {
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(new BigDecimal("30000"), new BigDecimal("3000"), false);

        assertThat(outcome.refundableAmount()).isEqualByComparingTo("30000");
        assertThat(outcome.deductedShippingFee()).isEqualByComparingTo("0");
        assertThat(outcome.deductsShippingFee()).isFalse();
    }

    @Test @DisplayName("배송 시작 후: 배송비 차감")
    void afterShipping_deductsShippingFee() {
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(new BigDecimal("30000"), new BigDecimal("3000"), true);

        assertThat(outcome.refundableAmount()).isEqualByComparingTo("27000");
        assertThat(outcome.deductedShippingFee()).isEqualByComparingTo("3000");
        assertThat(outcome.deductsShippingFee()).isTrue();
    }

    @Test @DisplayName("배송 시작 후에도 배송비 0 이면 전액 환불")
    void afterShipping_zeroFee_fullRefund() {
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(new BigDecimal("30000"), BigDecimal.ZERO, true);

        assertThat(outcome.refundableAmount()).isEqualByComparingTo("30000");
        assertThat(outcome.deductsShippingFee()).isFalse();
    }

    @Test @DisplayName("배송비가 결제액보다 크면 차감액은 결제액으로 clamp (환불 0)")
    void shippingFeeExceedsPaid_clamped() {
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(new BigDecimal("2000"), new BigDecimal("3000"), true);

        assertThat(outcome.refundableAmount()).isEqualByComparingTo("0");
        assertThat(outcome.deductedShippingFee()).isEqualByComparingTo("2000");
    }

    @Test @DisplayName("배송비 null 은 0 으로 취급")
    void nullShippingFee_treatedAsZero() {
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(new BigDecimal("30000"), null, true);

        assertThat(outcome.refundableAmount()).isEqualByComparingTo("30000");
        assertThat(outcome.deductsShippingFee()).isFalse();
    }

    @Test @DisplayName("결제 금액 음수는 예외")
    void negativePaid_throws() {
        assertThatThrownBy(() -> RefundPolicy.forOrder(new BigDecimal("-1"), BigDecimal.ZERO, false))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test @DisplayName("배송비 음수는 예외")
    void negativeFee_throws() {
        assertThatThrownBy(() -> RefundPolicy.forOrder(new BigDecimal("30000"), new BigDecimal("-1"), true))
                .isInstanceOf(OrderInvariantViolationException.class);
    }
}
