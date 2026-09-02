package github.lms.lemuel.batch.adapter.out.persistence;

import github.lms.lemuel.batch.domain.BatchRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code batch_run_history} 매핑.
 *
 * <p>표는 {@code V3__add_indexes_and_constraints.sql} 이 이미 만들어 뒀다 — 새로 만드는 게 아니라
 * 비어 있던 것을 잇는다. {@code triggered_by} 한 컬럼만 뒤에 추가됐다.
 */
@Entity
@Table(name = "batch_run_history")
public class BatchRunHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_name", nullable = false, length = 100)
    private String batchName;

    @Column(name = "run_id", nullable = false, length = 100)
    private String runId;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchRunStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "processed_count")
    private Integer processedCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    protected BatchRunHistoryJpaEntity() {
    }

    private BatchRunHistoryJpaEntity(String batchName, String runId, LocalDate targetDate,
                                     String triggeredBy, LocalDateTime startedAt) {
        this.batchName = batchName;
        this.runId = runId;
        this.targetDate = targetDate;
        this.triggeredBy = triggeredBy;
        this.startedAt = startedAt;
        this.status = BatchRunStatus.RUNNING;
        this.processedCount = 0;
    }

    static BatchRunHistoryJpaEntity started(String batchName, String runId, LocalDate targetDate,
                                            String triggeredBy, LocalDateTime startedAt) {
        return new BatchRunHistoryJpaEntity(batchName, runId, targetDate, triggeredBy, startedAt);
    }

    void succeed(int processedCount, LocalDateTime completedAt) {
        this.status = BatchRunStatus.SUCCEEDED;
        this.processedCount = processedCount;
        this.completedAt = completedAt;
    }

    /**
     * @param processedCount 실패 전까지 처리한 건수. {@code null} 이면 <b>덮어쓰지 않는다</b> —
     *     "모른다" 와 "0건" 은 다른 뜻이고, 열을 0 으로 채우면 그 구분이 사라진다.
     */
    void fail(Integer processedCount, String errorMessage, LocalDateTime completedAt) {
        this.status = BatchRunStatus.FAILED;
        if (processedCount != null) {
            this.processedCount = processedCount;
        }
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
    }

    public Long getId() {
        return id;
    }

    public String getBatchName() {
        return batchName;
    }

    public String getRunId() {
        return runId;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public BatchRunStatus getStatus() {
        return status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public Integer getProcessedCount() {
        return processedCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }
}
