package github.lms.lemuel.payment.adapter.in.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossPaymentConfirmRequestTest {

    @Test
    @DisplayName("getter/setter 왕복")
    void settersAndGetters_roundTrip() {
        TossPaymentConfirmRequest request = new TossPaymentConfirmRequest();

        request.setDbOrderId(1L);
        request.setPaymentKey("pay-key");
        request.setTossOrderId("toss-order-1");
        request.setAmount(15000L);

        assertThat(request.getDbOrderId()).isEqualTo(1L);
        assertThat(request.getPaymentKey()).isEqualTo("pay-key");
        assertThat(request.getTossOrderId()).isEqualTo("toss-order-1");
        assertThat(request.getAmount()).isEqualTo(15000L);
    }
}
