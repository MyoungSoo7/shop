package github.lms.lemuel.order.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import github.lms.lemuel.order.application.port.in.ClaimGiftUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 선물 수령 링크 소멸 배치 — 기한이 지난 미수령 선물을 닫는다.
 *
 * <p>실행 결과는 {@code batch_run_history} 에 남고,
 * {@code POST /admin/batch-runs/gift-claim-expiry/rerun} 으로 날짜분을 다시 돌린다.
 *
 * <p>이 배치는 <b>dry-run 을 지원하지 않는다</b> — 유스케이스에 그 인자가 없다. 지원하는 척하고
 * 조용히 실제로 처리하는 것보다 거절하는 편이 낫다({@link #supportsDryRun()}).
 */
@Component
public class GiftClaimExpiryScheduler implements RerunnableBatch {

    private static final Logger log = LoggerFactory.getLogger(GiftClaimExpiryScheduler.class);

    /** 원장·재실행 API 의 키. */
    public static final String BATCH_NAME = "gift-claim-expiry";

    private final ClaimGiftUseCase claimGiftUseCase;
    private final BatchRunRecorder recorder;
    private final int batchSize;

    public GiftClaimExpiryScheduler(ClaimGiftUseCase claimGiftUseCase,
                                    BatchRunRecorder recorder,
                                    @Value("${app.gift.expiry.batch-size:500}") int batchSize) {
        this.claimGiftUseCase = claimGiftUseCase;
        this.recorder = recorder;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.gift.expiry.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-gift-claim-expiry", lockAtMostFor = "PT30M")
    public void expire() {
        try {
            recorder.recordScheduled(BATCH_NAME, () -> expireAsOf(LocalDateTime.now()));
        } catch (RuntimeException exception) {
            log.error("선물 링크 소멸 배치 실패 — 다음 주기에 재시도한다", exception);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public String description() {
        return "선물 수령 링크 소멸 — 기한이 지난 미수령 선물을 닫는다";
    }

    @Override
    public boolean supportsDryRun() {
        return false;
    }

    @Override
    public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
        return BatchRunOutcome.succeeded(expireAsOf(BatchTargetDate.endOf(targetDate)));
    }

    private int expireAsOf(LocalDateTime asOf) {
        int closed = claimGiftUseCase.expireOverdue(asOf, batchSize);
        if (closed > 0) {
            log.info("선물 링크 소멸 배치: asOf={}, {}건", asOf, closed);
        }
        return closed;
    }
}
