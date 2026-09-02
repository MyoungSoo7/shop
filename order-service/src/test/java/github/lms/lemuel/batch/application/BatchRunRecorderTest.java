package github.lms.lemuel.batch.application;

import github.lms.lemuel.batch.FakeBatchRunLedger;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장 기록기의 계약.
 *
 * <p>여기서 지키려는 것은 셋이다 — (1) 원장이 배치를 죽이지 않는다, (2) 실패는 실패로 남는다,
 * (3) "일부 실패" 를 성공으로 적지 않는다. 셋 다 어겨도 컴파일은 통과하고 정상 경로 테스트도
 * 초록이라, 이 파일이 없으면 아무도 모른다.
 */
class BatchRunRecorderTest {

    private final FakeBatchRunLedger ledger = new FakeBatchRunLedger();

    @Test
    @DisplayName("정상 실행은 SUCCEEDED 와 처리 건수로 닫힌다")
    void 정상_실행() {
        int returned = ledger.recorder().recordScheduled("point-lot-expiry", () -> 7);

        assertThat(returned).isEqualTo(7);
        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.SUCCEEDED);
        assertThat(row.processedCount()).isEqualTo(7);
        assertThat(row.batchName()).isEqualTo("point-lot-expiry");
        assertThat(row.triggeredBy()).isEqualTo(BatchRunRecorder.TRIGGERED_BY_SCHEDULER);
        assertThat(row.targetDate()).isEqualTo(BatchTargetDate.today());
        assertThat(row.errorMessage()).isNull();
    }

    @Test
    @DisplayName("원장이 통째로 고장나도 배치 본문은 그대로 돈다 (fail-open)")
    void 원장_고장은_배치를_막지_않는다() {
        ledger.breakLedger();
        AtomicInteger ran = new AtomicInteger();

        int returned = ledger.recorder().recordScheduled("gift-card-expiry", () -> {
            ran.incrementAndGet();
            return 3;
        });

        // 관측 장치가 관측 대상을 멈추게 하면 순서가 뒤집힌 것이다.
        assertThat(ran.get()).isEqualTo(1);
        assertThat(returned).isEqualTo(3);
    }

    @Test
    @DisplayName("본문이 던지면 FAILED 로 닫고 예외는 그대로 올려보낸다")
    void 예외는_FAILED_로_남기고_다시_던진다() {
        assertThatThrownBy(() -> ledger.recorder().recordScheduled("payment-expiry", () -> {
            throw new IllegalStateException("PG 응답 없음");
        })).isInstanceOf(IllegalStateException.class);

        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.FAILED);
        // 스택트레이스가 아니라 원인 한 줄. 표가 로그 저장소가 되면 안 된다.
        assertThat(row.errorMessage()).isEqualTo("IllegalStateException: PG 응답 없음");
    }

    @Test
    @DisplayName("부분 실패는 예외 없이도 FAILED 로 남는다 — 성공으로 적으면 원장이 거짓말을 한다")
    void 부분_실패는_FAILED() {
        int returned = ledger.recorder().recordOutcome("payment-expiry", LocalDate.of(2026, 9, 1), "rerun:admin:kim",
                () -> BatchRunOutcome.partiallyFailed(9, "일부 건 만료 실패: expired=9, failed=2"));

        // 흐름은 성공 경로 그대로다 — 재실행 API 가 500 을 내지 않는다.
        assertThat(returned).isEqualTo(9);
        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(row.errorMessage()).contains("failed=2");
        assertThat(row.targetDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(row.triggeredBy()).isEqualTo("rerun:admin:kim");
    }

    @Test
    @DisplayName("실행마다 runId 가 다르다 — 같은 대상일의 두 실행을 가를 수 있어야 한다")
    void runId_는_실행마다_다르다() {
        BatchRunRecorder recorder = ledger.recorder();
        recorder.recordScheduled("point-lot-expiry", () -> 1);
        recorder.recordScheduled("point-lot-expiry", () -> 1);

        assertThat(ledger.rows()).hasSize(2);
        assertThat(ledger.rows().get(0).runId()).isNotEqualTo(ledger.rows().get(1).runId());
    }

    @Test
    @DisplayName("긴 실패 메시지는 잘라 담는다")
    void 실패_메시지_길이_상한() {
        String huge = "x".repeat(5_000);

        ledger.recorder().recordOutcome("audit-partition-ensure", BatchTargetDate.today(), "startup",
                () -> BatchRunOutcome.partiallyFailed(0, huge));

        assertThat(ledger.only().errorMessage()).hasSize(2_000);
    }
}
