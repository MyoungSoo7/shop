package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.batch.FakeBatchRunLedger;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 미입금 만료 스케줄러 — 유스케이스를 실행 모드(dryRun=false)로 호출하고, 그 결과를 원장에 남긴다.
 *
 * <p>원장은 목이 아니라 {@link FakeBatchRunLedger} 다. {@code BatchRunRecorder} 를 목으로 넣으면
 * {@code recordScheduledOutcome} 이 <b>본문 람다를 실행하지 않고</b> 0 을 돌려주므로, 유스케이스가
 * 호출됐는지 보는 이 클래스의 검증이 통째로 공허해진다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentExpirySchedulerTest {

    @Mock ExpirePendingPaymentsUseCase useCase;

    private final FakeBatchRunLedger ledger = new FakeBatchRunLedger();

    private PaymentExpiryScanner scheduler() {
        return new PaymentExpiryScanner(useCase, ledger.recorder());
    }

    @Test @DisplayName("현재 시각으로 실행 모드(dryRun=false) 호출한다")
    void invokesUseCaseInRealMode() {
        when(useCase.expireDue(any(), anyBoolean())).thenReturn(new ExpiryReport(0, 0, 0, 0, false));

        scheduler().scan();

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(useCase).expireDue(at.capture(), eq(false));
        assertThat(at.getValue()).isNotNull();
    }

    @Test @DisplayName("성공한 주기는 만료 건수와 함께 원장에 남는다")
    void recordsSuccessfulRun() {
        when(useCase.expireDue(any(), anyBoolean())).thenReturn(new ExpiryReport(7, 7, 0, 0, false));

        scheduler().scan();

        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.batchName()).isEqualTo(PaymentExpiryScanner.BATCH_NAME);
        assertThat(row.status()).isEqualTo(BatchRunStatus.SUCCEEDED);
        assertThat(row.processedCount()).isEqualTo(7);
        assertThat(row.triggeredBy()).isEqualTo("scheduler");
    }

    @Test @DisplayName("부분 실패는 FAILED 로 적히되 성공한 건수는 그대로 남는다")
    void recordsPartialFailureWithProcessedCount() {
        // 인자 순서는 (scanned, expired, skipped, failed, dryRun) 이다 — failed 를 skipped 자리에
        // 넣으면 부분 실패가 아니라 그냥 성공이 되어 이 테스트가 조용히 아무것도 검증하지 않는다.
        when(useCase.expireDue(any(), anyBoolean())).thenReturn(new ExpiryReport(7, 5, 0, 2, false));

        scheduler().scan();

        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.FAILED);
        // 실패했다고 처리 건수를 비워 두면 "그래서 몇 건은 됐나" 에 못 답한다 — 재실행 범위를
        // 정할 때 필요한 게 정확히 이 숫자다.
        assertThat(row.processedCount()).isEqualTo(5);
        assertThat(row.errorMessage()).contains("expired=5", "failed=2");
    }

    @Test @DisplayName("유스케이스가 던져도 스케줄러 스레드를 죽이지 않는다")
    void survivesUseCaseFailure() {
        when(useCase.expireDue(any(), anyBoolean())).thenThrow(new RuntimeException("DB 연결 끊김"));

        // 스케줄러에서 예외가 새면 이후 주기가 중단될 수 있다 — 잡아서 로깅하고 다음 주기를 기약한다.
        assertThatCode(() -> scheduler().scan()).doesNotThrowAnyException();
        // 다만 조용히 넘어가지는 않는다 — 삼킨 예외는 원장에 남아야 다음 날 아침에 보인다.
        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(row.errorMessage()).contains("DB 연결 끊김");
        // 예외로 튄 경우엔 몇 건까지 갔는지 모른다 — 0 이 아니라 null 이어야 그 구분이 남는다.
        assertThat(row.processedCount()).isNull();
    }
}
