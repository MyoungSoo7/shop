package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizePaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock PublishEventPort publishEventPort;
    @InjectMocks AuthorizePaymentUseCase authorizePaymentUseCase;

    @Test @DisplayName("결제 승인 성공")
    void authorize_success() {
        PaymentDomain payment = PaymentDomain.create(1L, new BigDecimal("20000"), "CARD");
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(pgClientPort.authorize(any(), any(), any())).thenReturn("pg-tx-456");
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDomain result = authorizePaymentUseCase.authorizePayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.getPgTransactionId()).isEqualTo("pg-tx-456");
        verify(publishEventPort).publishPaymentAuthorized(any());
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void authorize_notFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authorizePaymentUseCase.authorizePayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
