package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentGatewayTest {

    @Test
    @DisplayName("거래 ID prefix 로 PG 식별 - TOSS")
    void fromTransactionId_toss() {
        assertThat(PaymentGateway.fromTransactionId("TOSS:abc-123"))
                .isEqualTo(PaymentGateway.TOSS);
    }

    @Test
    @DisplayName("거래 ID prefix 로 PG 식별 - 대소문자 무관")
    void fromTransactionId_caseInsensitive() {
        assertThat(PaymentGateway.fromTransactionId("kcp:xyz"))
                .isEqualTo(PaymentGateway.KCP);
    }

    @Test
    @DisplayName("prefix 가 없는 레거시 거래 ID 는 MOCK 으로 폴백")
    void fromTransactionId_legacy() {
        assertThat(PaymentGateway.fromTransactionId("PG-uuid-no-colon"))
                .isEqualTo(PaymentGateway.MOCK);
    }

    @Test
    @DisplayName("null / blank 거래 ID 는 MOCK 폴백")
    void fromTransactionId_null() {
        assertThat(PaymentGateway.fromTransactionId(null)).isEqualTo(PaymentGateway.MOCK);
        assertThat(PaymentGateway.fromTransactionId("")).isEqualTo(PaymentGateway.MOCK);
        assertThat(PaymentGateway.fromTransactionId("   ")).isEqualTo(PaymentGateway.MOCK);
    }

    @Test
    @DisplayName("알 수 없는 prefix 는 MOCK 폴백 (운영 중 신규 PG 추가 시점의 잠시간 호환)")
    void fromTransactionId_unknownPrefix() {
        assertThat(PaymentGateway.fromTransactionId("STRIPE:abc"))
                .isEqualTo(PaymentGateway.MOCK);
    }
}
