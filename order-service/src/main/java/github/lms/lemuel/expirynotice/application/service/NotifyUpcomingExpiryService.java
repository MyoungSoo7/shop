package github.lms.lemuel.expirynotice.application.service;

import github.lms.lemuel.expirynotice.application.port.in.NotifyUpcomingExpiryUseCase;
import github.lms.lemuel.expirynotice.application.port.out.LoadExpiringItemsPort;
import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;
import github.lms.lemuel.expirynotice.domain.ExpirySubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 만료 예고 통보 — 단계 × 대상을 훑어 아직 안 보낸 건만 내보낸다.
 *
 * <p><b>이 배치는 스스로 낫는다.</b> 조회 창이 하루가 아니라 단계 구간 전체이고(D-7 은 만료까지
 * 1~7일 남은 것 전부), 이미 보낸 건은 원장 UNIQUE 가 거른다. 그래서 하루를 통째로 건너뛰어도
 * 다음 날 그 사람들이 같은 창에 그대로 남아 있어 통보를 받는다 — 배송 지연 스캐너처럼 창을
 * 놓치면 영영 못 잡는 구조가 아니다. 재실행 경로를 둔 것은 복구 때문이 아니라,
 * <b>D-1 만은 창이 하루라 놓치면 못 메우기 때문</b>이고 그때는 이미 늦었다는 사실을 남기기 위해서다.
 *
 * <p>여기 자체엔 트랜잭션이 없다. 건별 경계는 {@link ExpiryNoticeEmitter} 가 갖는다.
 */
@Service
public class NotifyUpcomingExpiryService implements NotifyUpcomingExpiryUseCase {

    private static final Logger log = LoggerFactory.getLogger(NotifyUpcomingExpiryService.class);

    private final LoadExpiringItemsPort loadPort;
    private final ExpiryNoticeEmitter emitter;

    public NotifyUpcomingExpiryService(LoadExpiringItemsPort loadPort, ExpiryNoticeEmitter emitter) {
        this.loadPort = loadPort;
        this.emitter = emitter;
    }

    @Override
    public NotifyExpiryResult notify(OffsetDateTime asOf, boolean dryRun, int limit) {
        NotifyExpiryResult total = NotifyExpiryResult.empty();
        for (ExpiryNoticeStage stage : ExpiryNoticeStage.values()) {
            for (ExpirySubject subject : ExpirySubject.values()) {
                total = total.plus(run(subject, stage, asOf, dryRun, limit));
            }
        }
        if (total.notified() > 0 || total.failed() > 0) {
            log.info("만료 예고 통보: asOf={}, dryRun={}, 통보={}, 기통보={}, 실패={}",
                    asOf, dryRun, total.notified(), total.skipped(), total.failed());
        }
        return total;
    }

    private NotifyExpiryResult run(ExpirySubject subject, ExpiryNoticeStage stage,
                                   OffsetDateTime asOf, boolean dryRun, int limit) {
        List<ExpiringItem> items = loadPort.findExpiringBetween(subject,
                asOf.plus(stage.floorLeadTime()), asOf.plus(stage.leadTime()), limit);
        if (items.isEmpty()) {
            return NotifyExpiryResult.empty();
        }
        if (dryRun) {
            // 원장을 건드리지 않는다 — dry-run 이 선점을 남기면 진짜 실행이 전부 스킵된다.
            // 다만 창에 든 건수는 "이미 보낸 것" 을 못 걸러 실제 발송량보다 크게 나온다.
            return new NotifyExpiryResult(0, items.size(), 0);
        }

        int notified = 0;
        int skipped = 0;
        int failed = 0;
        for (ExpiringItem item : items) {
            try {
                if (emitter.emit(item, stage)) {
                    notified++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                // 한 건의 실패가 나머지를 막지 않는다. 원장에 안 남았으므로 다음 주기에 다시 잡힌다.
                failed++;
                log.warn("만료 예고 통보 실패 — subject={}, id={}, stage={}: {}",
                        subject, item.subjectId(), stage, exception.getMessage());
            }
        }
        if (items.size() == limit) {
            // 상한에 정확히 걸렸다는 것은 더 있는데 못 봤을 수 있다는 뜻이다. 조용히 자르지 않는다.
            log.warn("만료 예고 조회가 상한에 닿았다 — subject={}, stage={}, limit={}. 남은 건은 다음 주기로 넘어간다",
                    subject, stage, limit);
        }
        return new NotifyExpiryResult(notified, skipped, failed);
    }
}
