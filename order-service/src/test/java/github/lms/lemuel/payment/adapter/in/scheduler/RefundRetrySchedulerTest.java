package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.RefundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundRetrySchedulerTest {

    @Mock LoadRefundPort loadRefundPort;
    @Mock RefundPaymentPort refundPaymentPort;
    @InjectMocks RefundRetryScheduler scheduler;

    private Refund failed(Long id, Long paymentId, String amount, String key) {
        Refund r = Refund.request(paymentId, new BigDecimal(amount), key, "FULL_REFUND");
        r.assignId(id);
        r.markFailed("이전 PG 실패");
        return r;
    }

    @Test @DisplayName("재시도 대상이 없으면 아무 것도 하지 않는다")
    void noDueRefunds_noop() {
        when(loadRefundPort.findRetryable(any())).thenReturn(List.of());

        scheduler.retryFailedRefunds();

        verifyNoInteractions(refundPaymentPort);
    }

    @Test @DisplayName("도래한 FAILED 환불을 저장된 금액·멱등키로 재호출한다")
    void retriesDueRefunds_withStoredAmountAndKey() {
        Refund r1 = failed(11L, 1L, "50000", "payment-1-full");
        Refund r2 = failed(22L, 2L, "3000", "partial-2-a");
        when(loadRefundPort.findRetryable(any())).thenReturn(List.of(r1, r2));

        scheduler.retryFailedRefunds();

        verify(refundPaymentPort).refundPayment(1L, new BigDecimal("50000"), "payment-1-full");
        verify(refundPaymentPort).refundPayment(2L, new BigDecimal("3000"), "partial-2-a");
    }

    @Test @DisplayName("한 건이 실패해도 나머지 건은 계속 재시도한다")
    void oneFailureDoesNotStopOthers() {
        Refund r1 = failed(11L, 1L, "50000", "payment-1-full");
        Refund r2 = failed(22L, 2L, "3000", "partial-2-a");
        when(loadRefundPort.findRetryable(any())).thenReturn(List.of(r1, r2));
        doThrow(new RefundException("PG 재실패", new RuntimeException()))
                .when(refundPaymentPort).refundPayment(eq(1L), any(), any());

        scheduler.retryFailedRefunds();

        // r1 이 예외를 던져도 r2 는 시도된다.
        verify(refundPaymentPort).refundPayment(1L, new BigDecimal("50000"), "payment-1-full");
        verify(refundPaymentPort).refundPayment(2L, new BigDecimal("3000"), "partial-2-a");
    }
}
