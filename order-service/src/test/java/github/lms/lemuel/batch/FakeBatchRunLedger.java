package github.lms.lemuel.batch;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.out.RecordBatchRunPort;
import github.lms.lemuel.batch.domain.BatchRunStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 인메모리 배치 실행 원장 — 스케줄러 단위 테스트가 "무엇이 원장에 적혔는가" 를 그대로 볼 수 있게 한다.
 *
 * <p>Mockito 목 대신 진짜 자료구조를 쓴다. 이 원장의 계약은 호출 횟수가 아니라 <b>남은 행의 모양</b>
 * (상태·처리건수·트리거 주체)이고, 목으로 검증하면 "begin 을 한 번 불렀다" 는 알아도
 * "그 행이 FAILED 로 끝났다" 는 못 본다.
 */
public class FakeBatchRunLedger implements RecordBatchRunPort {

    /** 원장 한 줄. */
    public record Row(long id, String batchName, String runId, LocalDate targetDate, String triggeredBy,
                      BatchRunStatus status, Integer processedCount, String errorMessage) { }

    private final AtomicLong sequence = new AtomicLong();
    private final List<Row> rows = new ArrayList<>();

    /** true 면 모든 적재 호출이 던진다 — fail-open(원장이 배치를 죽이지 않음) 검증용. */
    private boolean broken;

    public void breakLedger() {
        this.broken = true;
    }

    /** 이 원장을 물고 있는 실제 recorder. 테스트는 목이 아니라 진짜 recorder 를 통과시킨다. */
    public BatchRunRecorder recorder() {
        return new BatchRunRecorder(this);
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public Row only() {
        if (rows.size() != 1) {
            throw new AssertionError("원장에 정확히 1행을 기대했는데 " + rows.size() + "행이다: " + rows);
        }
        return rows.get(0);
    }

    @Override
    public Long begin(String batchName, String runId, LocalDate targetDate, String triggeredBy) {
        failIfBroken();
        long id = sequence.incrementAndGet();
        rows.add(new Row(id, batchName, runId, targetDate, triggeredBy, BatchRunStatus.RUNNING, null, null));
        return id;
    }

    @Override
    public void succeed(long id, int processedCount) {
        failIfBroken();
        replace(id, row -> new Row(row.id(), row.batchName(), row.runId(), row.targetDate(), row.triggeredBy(),
                BatchRunStatus.SUCCEEDED, processedCount, null));
    }

    @Override
    public void fail(long id, Integer processedCount, String errorMessage) {
        failIfBroken();
        // null 이면 기존 값을 유지한다 — 진짜 어댑터(BatchRunHistoryJpaEntity#fail)와 같은 규칙이라야
        // 이 가짜가 통과시킨 테스트가 운영에서도 같은 뜻을 갖는다.
        replace(id, row -> new Row(row.id(), row.batchName(), row.runId(), row.targetDate(), row.triggeredBy(),
                BatchRunStatus.FAILED,
                processedCount != null ? processedCount : row.processedCount(),
                errorMessage));
    }

    private void replace(long id, java.util.function.UnaryOperator<Row> update) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id() == id) {
                rows.set(i, update.apply(rows.get(i)));
                return;
            }
        }
        throw new AssertionError("원장에 없는 id 를 갱신하려 했다: " + id);
    }

    private void failIfBroken() {
        if (broken) {
            throw new IllegalStateException("원장 적재 실패(테스트가 의도한 고장)");
        }
    }
}
