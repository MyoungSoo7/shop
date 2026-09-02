package github.lms.lemuel.batch.application;

import github.lms.lemuel.batch.FakeBatchRunLedger;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchRerunServiceTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 9, 1);

    private final FakeBatchRunLedger ledger = new FakeBatchRunLedger();

    /** 호출 인자를 그대로 기록하는 최소 구현. 목이 아니라 진짜 객체라 인터페이스 계약이 그대로 보인다. */
    private static class StubBatch implements RerunnableBatch {
        private final String name;
        private final boolean dryRunSupported;
        private final BatchRunOutcome outcome;
        LocalDate seenDate;
        Boolean seenDryRun;
        int calls;

        StubBatch(String name, boolean dryRunSupported, BatchRunOutcome outcome) {
            this.name = name;
            this.dryRunSupported = dryRunSupported;
            this.outcome = outcome;
        }

        @Override public String batchName() { return name; }
        @Override public String description() { return name + " 설명"; }
        @Override public boolean supportsDryRun() { return dryRunSupported; }

        @Override
        public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
            calls++;
            seenDate = targetDate;
            seenDryRun = dryRun;
            return outcome;
        }
    }

    private BatchRerunService service(RerunnableBatch... batches) {
        return new BatchRerunService(List.of(batches), ledger.recorder());
    }

    @Test
    @DisplayName("재실행 1회는 원장에 정확히 한 줄이다 — 배치와 서비스가 각자 적으면 두 줄이 된다")
    void 재실행은_한_줄만_남긴다() {
        StubBatch batch = new StubBatch("point-lot-expiry", true, BatchRunOutcome.succeeded(12));

        int processed = service(batch).rerun("point-lot-expiry", TARGET, false, "admin:kim");

        assertThat(processed).isEqualTo(12);
        assertThat(batch.calls).isEqualTo(1);
        FakeBatchRunLedger.Row row = ledger.only();
        assertThat(row.status()).isEqualTo(BatchRunStatus.SUCCEEDED);
        assertThat(row.processedCount()).isEqualTo(12);
        assertThat(row.targetDate()).isEqualTo(TARGET);
    }

    @Test
    @DisplayName("사람이 돌렸다는 사실과 누가 돌렸는지가 원장에 남는다")
    void 트리거_주체를_남긴다() {
        service(new StubBatch("gift-card-expiry", true, BatchRunOutcome.succeeded(1)))
                .rerun("gift-card-expiry", TARGET, false, "admin:kim");

        assertThat(ledger.only().triggeredBy()).isEqualTo("rerun:admin:kim");
    }

    @Test
    @DisplayName("dry-run 은 실제 실행과 다른 표식으로 남는다 — 원장만 보고 둘을 구분할 수 있어야 한다")
    void dryRun_은_구분되어_남는다() {
        StubBatch batch = new StubBatch("gift-card-expiry", true, BatchRunOutcome.succeeded(4));

        service(batch).rerun("gift-card-expiry", TARGET, true, "admin:kim");

        assertThat(batch.seenDryRun).isTrue();
        assertThat(ledger.only().triggeredBy()).isEqualTo("rerun-dry:admin:kim");
    }

    @Test
    @DisplayName("대상일이 배치에 그대로 전달된다")
    void 대상일_전달() {
        StubBatch batch = new StubBatch("shipping-delay-scan", false, BatchRunOutcome.succeeded(0));

        service(batch).rerun("shipping-delay-scan", TARGET, false, "admin:kim");

        assertThat(batch.seenDate).isEqualTo(TARGET);
    }

    @Test
    @DisplayName("dry-run 미지원 배치에 dry-run 을 요청하면 거절한다 — 조용히 실제 실행으로 넘기지 않는다")
    void dryRun_미지원은_거절() {
        StubBatch batch = new StubBatch("gift-claim-expiry", false, BatchRunOutcome.succeeded(0));

        assertThatThrownBy(() -> service(batch).rerun("gift-claim-expiry", TARGET, true, "admin:kim"))
                .isInstanceOf(BatchRerunService.DryRunUnsupportedException.class);

        assertThat(batch.calls).isZero();
        // 돌지도 않은 실행이 원장에 남으면 안 된다.
        assertThat(ledger.rows()).isEmpty();
    }

    @Test
    @DisplayName("모르는 이름은 거절한다")
    void 모르는_배치는_거절() {
        assertThatThrownBy(() -> service().rerun("없는배치", TARGET, false, "admin:kim"))
                .isInstanceOf(BatchRerunService.UnknownBatchException.class);

        assertThat(ledger.rows()).isEmpty();
    }

    @Test
    @DisplayName("재실행 중 예외는 FAILED 로 남고 그대로 올라간다")
    void 재실행_실패도_남는다() {
        RerunnableBatch exploding = new RerunnableBatch() {
            @Override public String batchName() { return "payment-expiry"; }
            @Override public String description() { return "터지는 배치"; }
            @Override public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
                throw new IllegalStateException("PG 응답 없음");
            }
        };

        assertThatThrownBy(() -> service(exploding).rerun("payment-expiry", TARGET, false, "admin:kim"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.only().status()).isEqualTo(BatchRunStatus.FAILED);
    }

    @Test
    @DisplayName("목록은 이름순으로 안정 정렬된다 — 운영 화면의 선택지 순서가 빈 등록 순서에 흔들리면 안 된다")
    void 목록은_이름순() {
        BatchRerunService service = service(
                new StubBatch("shipping-delay-scan", false, BatchRunOutcome.succeeded(0)),
                new StubBatch("gift-card-expiry", true, BatchRunOutcome.succeeded(0)),
                new StubBatch("point-lot-expiry", true, BatchRunOutcome.succeeded(0)));

        assertThat(service.available()).extracting(RerunnableBatch::batchName)
                .containsExactly("gift-card-expiry", "point-lot-expiry", "shipping-delay-scan");
    }
}
