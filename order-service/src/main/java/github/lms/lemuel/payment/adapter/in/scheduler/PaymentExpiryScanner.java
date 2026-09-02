package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 미입금 결제 만료 스캐너.
 *
 * <p>입금 대기(READY)로 기한이 지난 가상계좌·무통장 결제를 만료시키고 그 주문을 취소해 재고를 되돌린다.
 *
 * <p><b>ShedLock 이름은 전역 유일해야 한다</b> — 같은 이름을 두 스케줄러가 공유하면 락 보유 기간 동안
 * 나머지가 조용히 스킵된다(컴파일도 CI 도 못 잡는 실장애 패턴). {@code order-payment-expiry} 는
 * 기존 9개 이름과 겹치지 않는다.
 *
 * <p>실행 결과는 {@code batch_run_history} 에 남는다. 이 배치는 <b>실패 건수를 결과에 담아
 * 돌려주는</b> 유일한 배치라, 원장의 {@code processed_count} 는 만료 성공 건수이고 실패 건은
 * 로그와 함께 {@code error_message} 에 요약된다 — "돌긴 돌았는데 일부가 실패" 를 성공으로
 * 적으면 원장이 거짓말을 한다.
 */
@Component
public class PaymentExpiryScanner implements RerunnableBatch {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryScanner.class);

    /** 원장·재실행 API 의 키. */
    public static final String BATCH_NAME = "payment-expiry";

    private final ExpirePendingPaymentsUseCase useCase;
    private final BatchRunRecorder recorder;

    public PaymentExpiryScanner(ExpirePendingPaymentsUseCase useCase, BatchRunRecorder recorder) {
        this.useCase = useCase;
        this.recorder = recorder;
    }

    @Scheduled(cron = "${app.payment-expiry.scan-cron:0 20 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-payment-expiry", lockAtMostFor = "PT20M")
    public void scan() {
        try {
            recorder.recordScheduledOutcome(BATCH_NAME, () -> expireDueAsOf(LocalDateTime.now(), false));
        } catch (RuntimeException e) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 중단될 수 있다 — 삼키지 말고 남기되 스레드는 지킨다.
            log.error("미입금 만료 배치 실행 실패 — 다음 주기에 재시도한다", e);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public String description() {
        return "미입금 결제 만료 — 기한이 지난 가상계좌·무통장 결제를 만료시키고 주문을 취소한다";
    }

    @Override
    public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
        return expireDueAsOf(BatchTargetDate.endOf(targetDate), dryRun);
    }

    private BatchRunOutcome expireDueAsOf(LocalDateTime asOf, boolean dryRun) {
        ExpiryReport report = useCase.expireDue(asOf, dryRun);
        if (report.failed() > 0) {
            log.warn("미입금 만료 배치에 실패 건 존재 — 운영 확인 필요: asOf={}, failed={}, expired={}",
                    asOf, report.failed(), report.expired());
            // 부분 실패를 원장에 성공으로 남기지 않는다 — 흐름은 그대로 두고 상태만 FAILED 로 적는다.
            return BatchRunOutcome.partiallyFailed(report.expired(),
                    "일부 건 만료 실패: expired=" + report.expired() + ", failed=" + report.failed());
        }
        return BatchRunOutcome.succeeded(report.expired());
    }
}
