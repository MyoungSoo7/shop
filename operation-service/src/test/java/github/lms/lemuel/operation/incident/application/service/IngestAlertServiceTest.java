package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.AlertCommand;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.IngestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestAlertServiceTest {

    @Mock
    AlertApplier alertApplier;

    private AlertCommand alert(String fingerprint) {
        return new AlertCommand(fingerprint, true, Map.of(), Map.of(), null, null);
    }

    @Test
    void 전건_성공_시_applied_가_전체_건수와_같다() {
        IngestAlertService service = new IngestAlertService(alertApplier);

        IngestResult result = service.ingest(List.of(alert("a"), alert("b")));

        assertThat(result).isEqualTo(new IngestResult(2, 2, 0));
    }

    @Test
    void 동시_경쟁의_unique_위반은_새_트랜잭션으로_1회_재시도해_병합한다() {
        doThrow(new DataIntegrityViolationException("uq_incident_active"))
                .doNothing()
                .when(alertApplier).apply(any());
        IngestAlertService service = new IngestAlertService(alertApplier);

        IngestResult result = service.ingest(List.of(alert("a")));

        assertThat(result).isEqualTo(new IngestResult(1, 1, 0));
        verify(alertApplier, times(2)).apply(any());
    }

    @Test
    void 한_건의_실패는_배치_전체를_막지_않고_failed_로만_집계된다() {
        doThrow(new RuntimeException("boom"))
                .doNothing()
                .when(alertApplier).apply(any());
        IngestAlertService service = new IngestAlertService(alertApplier);

        IngestResult result = service.ingest(List.of(alert("a"), alert("b")));

        assertThat(result).isEqualTo(new IngestResult(2, 1, 1));
    }

    @Test
    void 낙관적_락_충돌도_재시도로_수렴한다() {
        doThrow(new OptimisticLockingFailureException("version"))
                .doThrow(new OptimisticLockingFailureException("version"))
                .doNothing()
                .when(alertApplier).apply(any());
        IngestAlertService service = new IngestAlertService(alertApplier);

        IngestResult result = service.ingest(List.of(alert("a")));

        assertThat(result).isEqualTo(new IngestResult(1, 1, 0));
        verify(alertApplier, times(3)).apply(any());
    }

    @Test
    void 재시도_상한_소진_시_failed_로_집계하고_예외를_전파하지_않는다() {
        doThrow(new DataIntegrityViolationException("uq"))
                .when(alertApplier).apply(any());   // 항상 실패 → MAX_ATTEMPTS 소진
        IngestAlertService service = new IngestAlertService(alertApplier);

        IngestResult result = service.ingest(List.of(alert("a")));

        assertThat(result).isEqualTo(new IngestResult(1, 0, 1));
        verify(alertApplier, times(IngestAlertService.MAX_ATTEMPTS)).apply(any());
    }
}
