package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Alertmanager 알람 반영 오케스트레이터 — 트랜잭션 없이 alert 를 순회하고,
 * 건별 반영은 {@link AlertApplier} 의 독립 트랜잭션에 위임한다.
 *
 * <p>멱등성/경쟁 처리:
 * <ul>
 *   <li>repeat_interval 재전송 — refire 경로가 자연 멱등 (occurrenceCount 만 증가).</li>
 *   <li>동시 webhook 경쟁 — 이중 INSERT(uq_incident_active 위반)와 refire 낙관적 락 충돌을
 *       catch 하고 <b>새 트랜잭션으로 재시도</b>(최대 {@value #MAX_ATTEMPTS}회) → 매 시도가
 *       최신 상태를 다시 읽으므로 결국 refire 로 병합된다.</li>
 *   <li>한 건의 실패는 배치 전체를 막지 않는다 — 실패 건수만 집계·로그.
 *       (웹 어댑터는 항상 200 응답 — Alertmanager 재시도 폭주 방지, 유실은 재전송이 보상)</li>
 * </ul>
 */
@Service
public class IngestAlertService implements IngestAlertUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestAlertService.class);

    /** 동시 경쟁 재시도 상한 — N 개 webhook 이 겹쳐도 최악 N-1 회 충돌 후 수렴한다. */
    static final int MAX_ATTEMPTS = 5;

    private final AlertApplier alertApplier;

    public IngestAlertService(AlertApplier alertApplier) {
        this.alertApplier = alertApplier;
    }

    @Override
    public IngestResult ingest(List<AlertCommand> alerts) {
        int applied = 0;
        int failed = 0;
        for (AlertCommand alert : alerts) {
            try {
                applyWithConflictRetry(alert);
                applied++;
            } catch (Exception e) {
                failed++;
                log.error("alert 반영 실패 (repeat_interval 재전송이 보상): fingerprint={} firing={}",
                        alert.fingerprint(), alert.firing(), e);
            }
        }
        if (failed > 0) {
            log.warn("webhook 배치 부분 실패: received={} applied={} failed={}", alerts.size(), applied, failed);
        }
        return new IngestResult(alerts.size(), applied, failed);
    }

    private void applyWithConflictRetry(AlertCommand alert) {
        // 동시 그룹 알림 경쟁: 이중 INSERT 는 uq_incident_active 위반(DataIntegrityViolation),
        // 겹친 refire 는 @Version 충돌(OptimisticLockingFailure)로 나타난다. 실패한 트랜잭션은
        // rollback-only 로 오염됐으므로 매번 새 트랜잭션(새 apply 호출)으로 재시도 —
        // 재시도는 최신 상태를 다시 읽어 자연스럽게 refire 경로로 수렴한다.
        for (int attempt = 1; ; attempt++) {
            try {
                alertApplier.apply(alert);
                return;
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("동시 webhook 경쟁 감지 — 재시도 {}/{}: fingerprint={}",
                        attempt, MAX_ATTEMPTS, alert.fingerprint());
            }
        }
    }
}
