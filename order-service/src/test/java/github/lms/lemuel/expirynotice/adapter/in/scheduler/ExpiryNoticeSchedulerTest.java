package github.lms.lemuel.expirynotice.adapter.in.scheduler;

import github.lms.lemuel.batch.FakeBatchRunLedger;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import github.lms.lemuel.expirynotice.application.port.in.NotifyUpcomingExpiryUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryNoticeSchedulerTest {

    private final FakeBatchRunLedger ledger = new FakeBatchRunLedger();

    /** 호출 인자를 그대로 기록한다. 이 배치의 계약은 "어느 시각을 기준으로 돌았는가" 이므로 그걸 본다. */
    private static class RecordingUseCase implements NotifyUpcomingExpiryUseCase {
        record Call(OffsetDateTime asOf, boolean dryRun, int limit) { }

        final List<Call> calls = new ArrayList<>();
        NotifyExpiryResult answer = new NotifyExpiryResult(0, 0, 0);

        @Override
        public NotifyExpiryResult notify(OffsetDateTime asOf, boolean dryRun, int limit) {
            calls.add(new Call(asOf, dryRun, limit));
            return answer;
        }
    }

    private final RecordingUseCase useCase = new RecordingUseCase();

    private ExpiryNoticeScheduler scheduler() {
        return new ExpiryNoticeScheduler(useCase, ledger.recorder(), 2_000, 3);
    }

    @Test
    @DisplayName("스케줄 실행은 통보 건수와 함께 원장에 남는다")
    void 스케줄_실행은_원장에_남는다() {
        useCase.answer = new NotifyUpcomingExpiryUseCase.NotifyExpiryResult(12, 40, 0);

        scheduler().notifyUpcoming();

        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.batchName()).isEqualTo(ExpiryNoticeScheduler.BATCH_NAME);
        assertThat(row.status()).isEqualTo(BatchRunStatus.SUCCEEDED);
        // 처리 건수는 "새로 보낸 것" 이다. 기통보(40)까지 세면 매일 같은 숫자가 찍혀 증감을 못 읽는다.
        assertThat(row.processedCount()).isEqualTo(12);
        assertThat(row.triggeredBy()).isEqualTo("scheduler");
    }

    @Test
    @DisplayName("예외 없이 일부만 실패한 실행도 FAILED 로 남는다")
    void 부분_실패는_FAILED() {
        useCase.answer = new NotifyUpcomingExpiryUseCase.NotifyExpiryResult(9, 0, 2);

        scheduler().notifyUpcoming();

        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(row.errorMessage()).contains("failed=2");
    }

    @Test
    @DisplayName("재실행은 그 날 배치가 실제로 돌던 시각을 되살린다 — 자정으로 되돌리면 다른 집합이 나온다")
    void 재실행은_그_날의_실행_시각을_쓴다() {
        scheduler().rerun(LocalDate.of(2026, 8, 30), false);

        assertThat(useCase.calls).hasSize(1);
        // 03시 KST. 창 경계가 기준시각 기준이라 자정(00시)으로 잡으면 3시간치가 어긋난다.
        assertThat(useCase.calls.get(0).asOf())
                .isEqualTo(OffsetDateTime.of(2026, 8, 30, 3, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("dry-run 을 지원한다 — 문자를 실제로 쏘기 전에 규모를 볼 수 있어야 한다")
    void dryRun_지원() {
        assertThat(scheduler().supportsDryRun()).isTrue();

        scheduler().rerun(LocalDate.of(2026, 8, 30), true);

        assertThat(useCase.calls.get(0).dryRun()).isTrue();
        // 스케줄러 자신은 원장에 적지 않는다 — 재실행 기록은 BatchRerunService 몫이다.
        assertThat(ledger.rows()).isEmpty();
    }

    @Test
    @DisplayName("배치 상한이 유스케이스로 전달된다")
    void 상한_전달() {
        scheduler().notifyUpcoming();

        assertThat(useCase.calls.get(0).limit()).isEqualTo(2_000);
    }
}
