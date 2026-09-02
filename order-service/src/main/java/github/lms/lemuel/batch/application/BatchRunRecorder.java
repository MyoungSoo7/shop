package github.lms.lemuel.batch.application;

import github.lms.lemuel.batch.application.port.out.RecordBatchRunPort;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 배치 1회 실행을 {@code batch_run_history} 에 남긴다.
 *
 * <p>이 표는 {@code V3__add_indexes_and_constraints.sql} 이 만들어 두고 인덱스까지 네 개 걸어 놨지만
 * <b>쓰는 코드가 한 줄도 없었다</b>. 2026-08-20 정리 마이그레이션이 "order 가 계속 쓰는 공유 테이블"
 * 이라는 근거로 삭제를 면제해 준 표인데, 운영 확인 결과 <b>0행</b>이었다. 이 클래스가 그 근거를
 * 사실로 만든다.
 *
 * <p>ShedLock 이 남기는 것은 <i>락 획득</i>이지 <i>실행 결과</i>가 아니다. 배치가 절반 처리하고
 * 던졌는지, 아예 안 돌았는지는 락 표로 알 수 없다.
 *
 * <h2>원장은 배치를 죽이지 않는다</h2>
 * 원장 적재가 실패해도(표 부재·DB 순간 장애 등) 배치 본문은 그대로 돈다 — fail-open. 관측 장치가
 * 관측 대상을 멈추게 하는 건 순서가 뒤집힌 것이다. 대신 그 사실은 WARN 으로 남긴다.
 */
@Component
public class BatchRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(BatchRunRecorder.class);

    /** 스택트레이스 전문이 아니라 원인 한 줄만 남긴다. 표가 로그 저장소가 되면 안 된다. */
    private static final int MAX_ERROR_LENGTH = 2000;

    /** 스케줄에 의한 정규 실행. 운영자 재실행과 구분된다. */
    public static final String TRIGGERED_BY_SCHEDULER = "scheduler";

    private final RecordBatchRunPort port;

    public BatchRunRecorder(RecordBatchRunPort port) {
        this.port = port;
    }

    /** 스케줄 실행 — 대상일은 오늘, 트리거는 스케줄러. */
    public int recordScheduled(String batchName, IntSupplier body) {
        return record(batchName, BatchTargetDate.today(), TRIGGERED_BY_SCHEDULER, body);
    }

    /** 스케줄 실행 — 부분 실패를 결과로 돌려주는 배치용. */
    public int recordScheduledOutcome(String batchName, Supplier<BatchRunOutcome> body) {
        return recordOutcome(batchName, BatchTargetDate.today(), TRIGGERED_BY_SCHEDULER, body);
    }

    /**
     * 실행을 감싸 원장에 남긴다.
     *
     * @param body 처리 건수를 돌려주는 배치 본문
     * @return 본문이 돌려준 처리 건수
     */
    public int record(String batchName, LocalDate targetDate, String triggeredBy, IntSupplier body) {
        return recordOutcome(batchName, targetDate, triggeredBy,
                () -> BatchRunOutcome.succeeded(body.getAsInt()));
    }

    /**
     * 실행을 감싸 원장에 남긴다 — 본문이 <b>예외 없이 부분 실패</b>를 돌려줄 수 있는 형태.
     *
     * <p>"돌긴 돌았는데 일부가 실패" 를 SUCCEEDED 로 적으면 원장이 거짓말을 한다. 그렇다고 예외를
     * 던지게 하면 호출 흐름이 바뀐다 — 그래서 결과 객체로 받는다.
     */
    public int recordOutcome(String batchName, LocalDate targetDate, String triggeredBy,
                             Supplier<BatchRunOutcome> body) {
        String runId = UUID.randomUUID().toString();
        Long id = beginQuietly(batchName, runId, targetDate, triggeredBy);
        try {
            BatchRunOutcome outcome = body.get();
            if (id != null) {
                if (outcome.failureNote() == null) {
                    succeedQuietly(id, outcome.processedCount());
                } else {
                    // 부분 실패는 처리 건수를 <b>안다</b>. 여기서 안 넘기면 원장이 "실패"만 남기고
                    // "그중 몇 건은 됐나" 를 잃는다 — 재실행 범위를 정할 때 필요한 게 그 숫자다.
                    failQuietly(id, outcome.processedCount(), truncate(outcome.failureNote()));
                }
            }
            return outcome.processedCount();
        } catch (RuntimeException exception) {
            if (id != null) {
                // 예외로 튄 경우엔 몇 건까지 갔는지 알 방법이 없다 — null 로 남겨 "0건" 과 구분한다.
                failQuietly(id, null, describe(exception));
            }
            throw exception;
        }
    }

    private Long beginQuietly(String batchName, String runId, LocalDate targetDate, String triggeredBy) {
        try {
            return port.begin(batchName, runId, targetDate, triggeredBy);
        } catch (RuntimeException e) {
            log.warn("배치 실행 원장 시작 기록 실패 — 배치는 그대로 진행한다: batch={}, 원인={}",
                    batchName, e.getMessage());
            return null;
        }
    }

    private void succeedQuietly(long id, int processedCount) {
        try {
            port.succeed(id, processedCount);
        } catch (RuntimeException e) {
            log.warn("배치 실행 원장 완료 기록 실패: runId={}, 원인={}", id, e.getMessage());
        }
    }

    private void failQuietly(long id, Integer processedCount, String errorMessage) {
        try {
            port.fail(id, processedCount, errorMessage);
        } catch (RuntimeException e) {
            log.warn("배치 실행 원장 실패 기록 실패: runId={}, 원인={}", id, e.getMessage());
        }
    }

    private String describe(RuntimeException exception) {
        return truncate(exception.getClass().getSimpleName()
                + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
    }

    private String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
