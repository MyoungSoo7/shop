package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
 * 미입금 만료 스케줄러 — 유스케이스를 실행 모드(dryRun=false)로 호출하기만 한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentExpirySchedulerTest {

    @Mock ExpirePendingPaymentsUseCase useCase;
    @InjectMocks PaymentExpiryScanner scheduler;

    @Test @DisplayName("현재 시각으로 실행 모드(dryRun=false) 호출한다")
    void invokesUseCaseInRealMode() {
        when(useCase.expireDue(any(), anyBoolean())).thenReturn(new ExpiryReport(0, 0, 0, 0, false));

        scheduler.scan();

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(useCase).expireDue(at.capture(), eq(false));
        assertThat(at.getValue()).isNotNull();
    }

    @Test @DisplayName("유스케이스가 던져도 스케줄러 스레드를 죽이지 않는다")
    void survivesUseCaseFailure() {
        when(useCase.expireDue(any(), anyBoolean())).thenThrow(new RuntimeException("DB 연결 끊김"));

        // 스케줄러에서 예외가 새면 이후 주기가 중단될 수 있다 — 잡아서 로깅하고 다음 주기를 기약한다.
        assertThatCode(() -> scheduler.scan()).doesNotThrowAnyException();
    }
}
