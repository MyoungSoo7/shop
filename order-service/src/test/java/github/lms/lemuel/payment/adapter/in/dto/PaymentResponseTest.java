package github.lms.lemuel.payment.adapter.in.dto;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentResponseTest {

    @Test
    @DisplayName("PaymentDomain 으로부터 모든 필드를 매핑한다")
    void constructor_mapsFromDomain() {
        PaymentDomain domain = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("15000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "TOSS:tx-1", null, null, null);

        PaymentResponse response = new PaymentResponse(domain);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getOrderId()).isEqualTo(10L);
        assertThat(response.getAmount()).isEqualByComparingTo("15000");
        assertThat(response.getStatus()).isEqualTo("CAPTURED");
        assertThat(response.getPaymentMethod()).isEqualTo("CARD");
        assertThat(response.getPgTransactionId()).isEqualTo("TOSS:tx-1");
    }

    @Test
    @DisplayName("기본 생성자 + setter 왕복")
    void noArgsConstructor_settersRoundTrip() {
        PaymentResponse response = new PaymentResponse();
        LocalDateTime now = LocalDateTime.now();

        response.setId(2L);
        response.setOrderId(20L);
        response.setAmount(new BigDecimal("30000"));
        response.setStatus("READY");
        response.setPaymentMethod("KAKAO_PAY");
        response.setPgTransactionId(null);
        response.setCreatedAt(now);
        response.setUpdatedAt(now);

        assertThat(response.getId()).isEqualTo(2L);
        assertThat(response.getOrderId()).isEqualTo(20L);
        assertThat(response.getAmount()).isEqualByComparingTo("30000");
        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getPaymentMethod()).isEqualTo("KAKAO_PAY");
        assertThat(response.getPgTransactionId()).isNull();
        assertThat(response.getCreatedAt()).isEqualTo(now);
        assertThat(response.getUpdatedAt()).isEqualTo(now);
    }
}
