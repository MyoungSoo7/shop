package github.lms.lemuel.sellertier.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase.TierEvaluationReport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 등급 자동 재산정 배치 (ADR 0031).
 *
 * <p><b>기본 비활성이다.</b> 임계 거래액이 재무 승인 전이라, 켜져 있으면 임의의 값으로 승급이 돌아
 * 수수료 수입이 조용히 깎인다. 임계를 확정하고 {@code app.seller-tier.auto-evaluate.enabled=true} 를
 * 명시해야 비로소 돈다 — 그때까지는 관리자 콘솔의 dryRun 으로만 확인한다.
 *
 * <p>ShedLock 이름은 전역 유일해야 한다 — 겹치면 락 보유 기간 동안 나머지 배치가 조용히 스킵된다
 * (scheduler-lock-gate 테스트가 리포 전수로 강제).
 *
 * <p><b>이 배치는 한 달에 한 번만 돈다.</b> 1일 03:00 에 파드가 떠 있지 않았거나 락을 잡은 파드가
 * 죽으면 그 달 평가는 통째로 사라지고, 다음 기회는 <b>한 달 뒤</b>다. 연속 미달 카운트가 곧
 * '연속 몇 달'이라 한 달 누락은 등급 판정 자체를 틀리게 만든다 — 이 배치가 재실행 경로를 가장
 * 절실히 필요로 하는 이유다
 * ({@code POST /admin/batch-runs/seller-tier-evaluate/rerun}).
 */
@Component
@ConditionalOnProperty(name = "app.seller-tier.auto-evaluate.enabled", havingValue = "true")
public class SellerTierEvaluationScheduler implements RerunnableBatch {

    private static final Logger log = LoggerFactory.getLogger(SellerTierEvaluationScheduler.class);

    /** 원장·재실행 API 의 키. */
    public static final String BATCH_NAME = "seller-tier-evaluate";

    private final EvaluateSellerTiersUseCase useCase;
    private final BatchRunRecorder recorder;
    private final int batchLimit;

    public SellerTierEvaluationScheduler(EvaluateSellerTiersUseCase useCase,
                                         BatchRunRecorder recorder,
                                         @Value("${app.seller-tier.batch-limit:1000}") int batchLimit) {
        this.useCase = useCase;
        this.recorder = recorder;
        this.batchLimit = batchLimit;
    }

    /** 매월 1일 03:00 KST — 월 단위 평가라 연속 미달 카운트가 곧 '연속 몇 달'이 된다. */
    @Scheduled(cron = "${app.seller-tier.evaluate-cron:0 0 3 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-seller-tier-evaluate", lockAtMostFor = "PT30M")
    public void evaluate() {
        try {
            recorder.recordScheduledOutcome(BATCH_NAME, () -> evaluateAsOf(LocalDate.now(), false));
        } catch (RuntimeException e) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 중단될 수 있다.
            log.error("등급 재산정 배치 실패 — 다음 주기에 재시도한다", e);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public String description() {
        return "셀러 등급 재산정 — 월 단위로 거래액을 평가해 승급·강등한다 (기본 비활성)";
    }

    @Override
    public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
        return evaluateAsOf(targetDate, dryRun);
    }

    private BatchRunOutcome evaluateAsOf(LocalDate asOf, boolean dryRun) {
        TierEvaluationReport report = useCase.evaluate(asOf, dryRun, batchLimit);
        log.info("등급 재산정: asOf={}, dryRun={}, 평가={}, 승급={}, 강등={}, 유예={}, 보호={}, 실패={}",
                asOf, dryRun, report.evaluated(), report.promoted(), report.demoted(),
                report.held(), report.guarded(), report.failed());
        if (report.failed() > 0) {
            log.warn("등급 재산정에 실패 건 존재 — 운영 확인 필요: failed={}", report.failed());
            return BatchRunOutcome.partiallyFailed(report.evaluated(),
                    "일부 셀러 평가 실패: evaluated=" + report.evaluated() + ", failed=" + report.failed());
        }
        return BatchRunOutcome.succeeded(report.evaluated());
    }
}
