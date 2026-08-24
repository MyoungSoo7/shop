package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.application.service.RefundLifecycle;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.RefundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RefundPaymentUseCase 의 PG 가 이미 {@link RefundException} 을 던지는 경로(추가 래핑 없이
 * 그대로 재전파) 를 검증한다. 원시 RuntimeException 을 감싸는 경로는
 * {@link RefundPaymentUseCaseTest#refund_pgFailure_recordsFailedAndDoesNotMutate()} 가 이미 커버.
 */
@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseExtraTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadRefundPort loadRefundPort;
    @Mock SaveRefundPort saveRefundPort;
    @Mock RefundLifecycle refundLifecycle;
    @InjectMocks RefundPaymentUseCase refundPaymentUseCase;

    private PaymentDomain capturedPayment() {
        return PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx-123", null, null, null);
    }

    @Test
    @DisplayName("PG 가 RefundException 을 직접 던지면 추가 래핑 없이 그대로 재전파하고 FAILED 이력을 남긴다")
    void refund_pgThrowsRefundExceptionDirectly_propagatesAsIs() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(refundLifecycle.begin(any(), any(), any(), any())).thenAnswer(inv -> {
            Refund r = Refund.request(inv.getArgument(0), inv.getArgument(1),
                    inv.getArgument(2), inv.getArgument(3));
            r.assignId(555L);
            return r;
        });
        RefundException pgException = new RefundException("PG rejected: insufficient balance");
        doThrow(pgException).when(pgClientPort).refund(eq("pg-tx-123"), any(), any());

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(1L))
                .isSameAs(pgException);

        verify(refundLifecycle).fail(eq(555L), eq("PG rejected: insufficient balance"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(updateOrderStatusPort, never()).updateOrderStatus(any(), any());
        verify(publishEventPort, never()).publishPaymentRefunded(any(), any(), any(), any(), any());
    }
}
