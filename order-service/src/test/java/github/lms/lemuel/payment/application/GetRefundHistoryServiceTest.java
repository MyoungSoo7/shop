package github.lms.lemuel.payment.application;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetRefundHistoryServiceTest {

    @Mock LoadRefundPort loadRefundPort;
    @InjectMocks GetRefundHistoryService service;

    @Test
    @DisplayName("결제별 환불 이력 위임")
    void delegates() {
        Refund r = Refund.request(1L, new BigDecimal("1000"), "key-1", "reason");
        when(loadRefundPort.findAllByPaymentId(1L)).thenReturn(List.of(r));

        List<Refund> result = service.getRefundsByPaymentId(1L);
        assertThat(result).containsExactly(r);
    }

    @Test
    @DisplayName("paymentId 유효성 검증")
    void rejectsInvalidPaymentId() {
        assertThatThrownBy(() -> service.getRefundsByPaymentId(null))
                .isInstanceOf(PaymentInvariantViolationException.class);
        assertThatThrownBy(() -> service.getRefundsByPaymentId(0L))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }
}
