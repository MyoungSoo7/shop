package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.batch.FakeBatchRunLedger;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.RefundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * 원장은 <b>목이 아니라 가짜 구현</b>이다. {@code BatchRunRecorder} 를 목으로 넣으면
     * {@code recordOutcome} 이 아무것도 안 하고 0 을 돌려주므로 <b>배치 본문이 아예 안 돈다</b> —
     * 그러면 이 클래스의 모든 검증(재호출·부분실패 계속)이 조용히 공허해진다.
     */
    private final FakeBatchRunLedger ledger = new FakeBatchRunLedger();

    private RefundRetryScheduler scheduler() {
        return new RefundRetryScheduler(loadRefundPort, refundPaymentPort, ledger.recorder());
    }

    private Refund failed(Long id, Long paymentId, String amount, String key) {
        Refund r = Refund.request(paymentId, new BigDecimal(amount), key, "FULL_REFUND");
        r.assignId(id);
        r.markFailed("이전 PG 실패");
        return r;
    }

    @Test @DisplayName("재시도 대상이 없으면 아무 것도 하지 않는다")
    void noDueRefunds_noop() {
        when(loadRefundPort.findRetryable(any())).thenReturn(List.of());

        scheduler().retryFailedRefunds();

        verifyNoInteractions(refundPaymentPort);
        // 폴링만 한 주기는 원장에 남지 않는다 — 매 분 SUCCEEDED 0건이 쌓이면 "돌긴 도는가" 라는
        // 질문에는 답하지만 "일했는가" 는 그 잡음에 묻힌다.
        assertThat(ledger.rows()).isEmpty();
    }

    @Test @DisplayName("도래한 FAILED 환불을 저장된 금액·멱등키로 재호출한다")
    void retriesDueRefunds_withStoredAmountAndKey() {
        Refund r1 = failed(11L, 1L, "50000", "payment-1-full");
        Refund r2 = failed(22L, 2L, "3000", "partial-2-a");
        when(loadRefundPort.findRetryable(any())).thenReturn(List.of(r1, r2));

        scheduler().retryFailedRefunds();

        verify(refundPaymentPort).refundPayment(1L, new BigDecimal("50000"), "payment-1-full");
        verify(refundPaymentPort).refundPayment(2L, new BigDecimal("3000"), "partial-2-a");
        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.batchName()).isEqualTo(RefundRetryScheduler.BATCH_NAME);
        assertThat(row.status()).isEqualTo(BatchRunStatus.SUCCEEDED);
        assertThat(row.processedCount()).isEqualTo(2);
    }

    @Test @DisplayName("한 건이 실패해도 나머지 건은 계속 재시도한다")
    void oneFailureDoesNotStopOthers() {
        Refund r1 = failed(11L, 1L, "50000", "payment-1-full");
        Refund r2 = failed(22L, 2L, "3000", "partial-2-a");
        when(loadRefundPort.findRetryable(any())).thenReturn(List.of(r1, r2));
        doThrow(new RefundException("PG 재실패", new RuntimeException()))
                .when(refundPaymentPort).refundPayment(eq(1L), any(), any());

        scheduler().retryFailedRefunds();

        // r1 이 예외를 던져도 r2 는 시도된다.
        verify(refundPaymentPort).refundPayment(1L, new BigDecimal("50000"), "payment-1-full");
        verify(refundPaymentPort).refundPayment(2L, new BigDecimal("3000"), "partial-2-a");
        // 그리고 그 사실이 원장에 남는다. "돌긴 돌았는데 한 건 실패" 를 SUCCEEDED 로 적으면
        // 원장이 거짓말을 한다 — 처리 건수는 성공한 1건만 적고 상태는 FAILED 다.
        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(row.processedCount()).isEqualTo(1);
        assertThat(row.errorMessage()).contains("succeeded=1", "failed=1");
    }
}
