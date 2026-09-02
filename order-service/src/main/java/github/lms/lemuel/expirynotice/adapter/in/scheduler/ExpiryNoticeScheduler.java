package github.lms.lemuel.expirynotice.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import github.lms.lemuel.expirynotice.application.port.in.NotifyUpcomingExpiryUseCase;
import github.lms.lemuel.expirynotice.application.port.in.NotifyUpcomingExpiryUseCase.NotifyExpiryResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 만료 예고 통보 배치 — 포인트 로트·기프트카드·선물 수령권이 사라지기 전에 알린다.
 *
 * <p><b>소멸보다 먼저 돈다.</b> 포인트 소멸이 03:40, 기프트카드 소멸이 03:50 이므로 이 배치는 03:10 이다.
 * 순서가 뒤집히면 "오늘 사라질 것" 이 소멸 배치에 먼저 지워져, 마지막 예고(D-1)가 대상 없는 빈 실행이 된다.
 *
 * <p><b>ShedLock 이름은 전역 유일해야 한다.</b> {@code order-expiry-notice} 는 기존 9개와 겹치지 않는다.
 * 같은 이름을 나눠 쓰면 락 보유 기간 동안 나머지가 조용히 스킵된다(ofDentis 레거시의 실장애 패턴).
 *
 * <p>재실행은 <b>그 날 03:10 시점</b>을 다시 계산한다. 대상일의 자정이 아니라 그 날의 실행 시각을 쓰는
 * 이유는, 창 경계가 실행 시각 기준으로 잡히기 때문이다 — 자정으로 되돌리면 그 날 실제로 나갔어야 할
 * 집합과 다른 집합이 나온다.
 */
@Component
public class ExpiryNoticeScheduler implements RerunnableBatch {

    private static final Logger log = LoggerFactory.getLogger(ExpiryNoticeScheduler.class);

    /** 원장·재실행 API 의 키. */
    public static final String BATCH_NAME = "expiry-notice";

    private final NotifyUpcomingExpiryUseCase useCase;
    private final BatchRunRecorder recorder;
    private final int batchLimit;
    private final int runHour;

    public ExpiryNoticeScheduler(NotifyUpcomingExpiryUseCase useCase,
                                 BatchRunRecorder recorder,
                                 @Value("${app.expiry-notice.batch-limit:2000}") int batchLimit,
                                 @Value("${app.expiry-notice.run-hour:3}") int runHour) {
        this.useCase = useCase;
        this.recorder = recorder;
        this.batchLimit = batchLimit;
        this.runHour = runHour;
    }

    @Scheduled(cron = "${app.expiry-notice.cron:0 10 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-expiry-notice", lockAtMostFor = "PT30M")
    public void notifyUpcoming() {
        try {
            recorder.recordScheduledOutcome(BATCH_NAME, () -> notifyAsOf(OffsetDateTime.now(), false));
        } catch (RuntimeException exception) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 멈출 수 있다 — 남기되 스레드는 지킨다.
            log.error("만료 예고 통보 배치 실패 — 다음 주기에 재시도한다", exception);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public String description() {
        return "만료 예고 통보 — 포인트·기프트카드·선물수령권이 사라지기 전에 알린다 (D-30/D-7/D-1)";
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
        return notifyAsOf(BatchTargetDate.atHourWithOffset(targetDate, runHour), dryRun);
    }

    private BatchRunOutcome notifyAsOf(OffsetDateTime asOf, boolean dryRun) {
        NotifyExpiryResult result = useCase.notify(asOf, dryRun, batchLimit);
        if (result.failed() > 0) {
            // 예외 없이 일부만 실패한 실행이다. 성공으로 적으면 원장이 거짓말을 한다.
            return BatchRunOutcome.partiallyFailed(result.notified(),
                    "일부 통보 실패: notified=" + result.notified() + ", failed=" + result.failed());
        }
        return BatchRunOutcome.succeeded(result.notified());
    }
}
