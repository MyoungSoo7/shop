package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.ClaimOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 이벤트 발행 폴러 (멀티워커).
 *
 * <p>일정 주기로 PENDING outbox 레코드를 {@code FOR UPDATE SKIP LOCKED} 로 claim 하여
 * {@link OutboxBatchEventPublisher} 로 배치 발행한다.
 *
 * <p><b>수평 확장</b>: 여러 인스턴스가 동시에 폴링해도 SKIP LOCKED 로 서로 겹치지 않는 행만
 * 가져가므로, 단일 ShedLock 으로 직렬화하던 기존 구조와 달리 인스턴스 수만큼 발행 처리량이 늘어난다.
 * claim 에는 리스(claimed_at)가 찍혀, 발행 완료 전 워커가 죽어도 리스 만료 후 다른 워커가 회수한다.
 */
@Component
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);
    private static final int BATCH_SIZE = 100;
    /** claim 리스 — 이 시간 안에 발행이 끝나지 않으면 다른 워커가 회수 가능. 폴링 주기보다 충분히 길게. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(1);

    /** 인스턴스 식별자 — claimed_by 로 기록되어 어느 워커가 잡았는지 추적 가능. */
    private final String workerId = "outbox-" + UUID.randomUUID();

    private final ClaimOutboxEventPort claimOutboxEventPort;
    private final LoadOutboxEventPort loadOutboxEventPort;
    private final OutboxBatchEventPublisher batchEventPublisher;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingGauge = new AtomicLong(0L);
    private final AtomicLong failedGauge = new AtomicLong(0L);

    public OutboxPublisherScheduler(ClaimOutboxEventPort claimOutboxEventPort,
                                    LoadOutboxEventPort loadOutboxEventPort,
                                    OutboxBatchEventPublisher batchEventPublisher,
                                    MeterRegistry meterRegistry) {
        this.claimOutboxEventPort = claimOutboxEventPort;
        this.loadOutboxEventPort = loadOutboxEventPort;
        this.batchEventPublisher = batchEventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("outbox.pending.count", pendingGauge, AtomicLong::get)
                .description("outbox_events 테이블의 PENDING 건수")
                .register(meterRegistry);
        Gauge.builder("outbox.failed.count", failedGauge, AtomicLong::get)
                .description("outbox_events 테이블의 FAILED 건수 (DLQ 알람 대상)")
                .register(meterRegistry);
    }

    /**
     * PENDING outbox 레코드를 claim 후 배치 발행.
     *
     * <p>ShedLock 없음 — 다중 인스턴스 병렬 폴링은 claim 단계의 SKIP LOCKED 로 안전하게 분할된다.
     *
     * <p>주기 실행은 {@link OutboxPollingTrigger} 가 건다. 여기에 {@code @Scheduled} 를 다시 붙이면
     * 트리거를 꺼도 폴링이 살아남는다 — {@code OutboxPollingTriggerTest} 가 그 회귀를 막는다.
     */
    public void publishPendingEvents() {
        pendingGauge.set(loadOutboxEventPort.countPending());
        failedGauge.set(loadOutboxEventPort.countFailed());

        List<OutboxEvent> claimed = claimOutboxEventPort.claimPending(BATCH_SIZE, CLAIM_LEASE, workerId);
        if (claimed.isEmpty()) {
            return;
        }

        log.debug("Outbox polling: {} events claimed by {}", claimed.size(), workerId);

        OutboxBatchEventPublisher.PublishOutcome outcome = batchEventPublisher.publishBatch(claimed);

        if (outcome.published() > 0 || outcome.failed() > 0) {
            log.info("Outbox batch complete: published={}, failed={}, batchSize={}",
                    outcome.published(), outcome.failed(), claimed.size());
        }
    }
}
