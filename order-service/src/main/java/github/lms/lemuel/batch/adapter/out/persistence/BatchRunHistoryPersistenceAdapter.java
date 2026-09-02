package github.lms.lemuel.batch.adapter.out.persistence;

import github.lms.lemuel.batch.application.port.out.RecordBatchRunPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 원장 적재 어댑터.
 *
 * <p>세 메서드 모두 {@code REQUIRES_NEW} 다. 배치 본문의 트랜잭션에 얹으면 <b>본문이 롤백될 때
 * 실패 기록도 같이 롤백된다</b> — 남겨야 할 단 한 건이 바로 그 건인데 그게 사라진다.
 * "실패를 적는 코드가 실패와 함께 지워지는" 구조는 조용해서 몇 달을 간다.
 */
@Component
public class BatchRunHistoryPersistenceAdapter implements RecordBatchRunPort {

    private final BatchRunHistoryJpaRepository repository;

    public BatchRunHistoryPersistenceAdapter(BatchRunHistoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(String batchName, String runId, LocalDate targetDate, String triggeredBy) {
        return repository.save(BatchRunHistoryJpaEntity.started(
                batchName, runId, targetDate, triggeredBy, LocalDateTime.now())).getId();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(long id, int processedCount) {
        repository.findById(id).ifPresent(entity -> entity.succeed(processedCount, LocalDateTime.now()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long id, Integer processedCount, String errorMessage) {
        repository.findById(id).ifPresent(entity -> entity.fail(processedCount, errorMessage, LocalDateTime.now()));
    }
}
