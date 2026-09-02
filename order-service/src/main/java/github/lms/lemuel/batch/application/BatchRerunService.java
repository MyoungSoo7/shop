package github.lms.lemuel.batch.application;

import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영자 재실행 진입점.
 *
 * <p>재실행도 <b>원장에 남는다</b>. 남기지 않으면 "그때 누가 손으로 다시 돌렸다" 가 기억에만 있게 되고,
 * 다음 사람은 그 날 수치가 왜 두 번 계산됐는지 알 방법이 없다.
 */
@Service
public class BatchRerunService {

    private static final Logger log = LoggerFactory.getLogger(BatchRerunService.class);

    private final Map<String, RerunnableBatch> batches;
    private final BatchRunRecorder recorder;

    public BatchRerunService(List<RerunnableBatch> batches, BatchRunRecorder recorder) {
        this.batches = batches.stream()
                .sorted(Comparator.comparing(RerunnableBatch::batchName))
                .collect(LinkedHashMap::new,
                        (map, batch) -> map.put(batch.batchName(), batch),
                        LinkedHashMap::putAll);
        this.recorder = recorder;
    }

    /** 재실행 가능한 배치 목록. 운영 화면이 무엇을 고를 수 있는지 여기서 온다. */
    public List<RerunnableBatch> available() {
        return List.copyOf(batches.values());
    }

    /**
     * @param actor 감사용 실행자 식별자. 스케줄러가 아니라 사람이 돌렸다는 사실이 원장에 남아야 한다.
     * @throws UnknownBatchException 이름이 없거나 재실행 대상이 아닐 때
     * @throws DryRunUnsupportedException 그 배치가 dry-run 을 지원하지 않는데 요청했을 때
     */
    public int rerun(String batchName, LocalDate targetDate, boolean dryRun, String actor) {
        RerunnableBatch batch = batches.get(batchName);
        if (batch == null) {
            throw new UnknownBatchException(batchName);
        }
        if (dryRun && !batch.supportsDryRun()) {
            throw new DryRunUnsupportedException(batchName);
        }
        log.info("배치 수동 재실행: batch={}, targetDate={}, dryRun={}, actor={}",
                batchName, targetDate, dryRun, actor);
        String triggeredBy = (dryRun ? "rerun-dry:" : "rerun:") + actor;
        return recorder.recordOutcome(batchName, targetDate, triggeredBy,
                () -> batch.rerun(targetDate, dryRun));
    }

    /** 이름 미상 — 400 으로 돌려주기 위한 표식. */
    public static class UnknownBatchException extends RuntimeException {
        public UnknownBatchException(String batchName) {
            super("재실행할 수 있는 배치가 아니다: " + batchName);
        }
    }

    /** dry-run 미지원 — 조용히 실제 실행으로 넘기면 안 되므로 거절한다. */
    public static class DryRunUnsupportedException extends RuntimeException {
        public DryRunUnsupportedException(String batchName) {
            super("이 배치는 dry-run 을 지원하지 않는다: " + batchName);
        }
    }
}
