package github.lms.lemuel.payment.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundExceptionTest {

    @Test
    @DisplayName("RefundException 은 message·cause 를 보존한다")
    void messageAndCause() {
        RuntimeException cause = new IllegalStateException("root");
        RefundException onlyMessage = new RefundException("환불 실패");
        RefundException withCause = new RefundException("환불 실패", cause);

        assertThat(onlyMessage.getMessage()).isEqualTo("환불 실패");
        assertThat(onlyMessage.getCause()).isNull();
        assertThat(withCause.getMessage()).isEqualTo("환불 실패");
        assertThat(withCause.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("환불 하위 예외들은 RefundException 계층에 속한다")
    void subtypes() {
        assertThat(new RefundExceedsPaymentException("초과"))
                .isInstanceOf(RefundException.class)
                .hasMessage("초과");
        assertThat(new MissingIdempotencyKeyException("키 누락"))
                .isInstanceOf(RefundException.class)
                .hasMessage("키 누락");
    }
}
