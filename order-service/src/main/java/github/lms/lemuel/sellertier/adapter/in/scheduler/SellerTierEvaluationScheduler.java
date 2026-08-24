package github.lms.lemuel.sellertier.adapter.in.scheduler;

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
 */
@Component
@ConditionalOnProperty(name = "app.seller-tier.auto-evaluate.enabled", havingValue = "true")
public class SellerTierEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SellerTierEvaluationScheduler.class);

    private final EvaluateSellerTiersUseCase useCase;
    private final int batchLimit;

    public SellerTierEvaluationScheduler(EvaluateSellerTiersUseCase useCase,
                                         @Value("${app.seller-tier.batch-limit:1000}") int batchLimit) {
        this.useCase = useCase;
        this.batchLimit = batchLimit;
    }

    /** 매월 1일 03:00 KST — 월 단위 평가라 연속 미달 카운트가 곧 '연속 몇 달'이 된다. */
    @Scheduled(cron = "${app.seller-tier.evaluate-cron:0 0 3 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-seller-tier-evaluate", lockAtMostFor = "PT30M")
    public void evaluate() {
        try {
            TierEvaluationReport report = useCase.evaluate(LocalDate.now(), false, batchLimit);
            if (report.failed() > 0) {
                log.warn("등급 재산정에 실패 건 존재 — 운영 확인 필요: failed={}", report.failed());
            }
        } catch (RuntimeException e) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 중단될 수 있다.
            log.error("등급 재산정 배치 실패 — 다음 주기에 재시도한다", e);
        }
    }
}
