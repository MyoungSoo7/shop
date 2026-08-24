package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.ClaimOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 이벤트 배치 발행기.
 *
 * <p>한 폴링 주기에 claim 한 이벤트들을 <b>한꺼번에 비동기 dispatch</b> 한 뒤 결과를 모아
 * <b>한 트랜잭션에서 일괄 영속</b>한다. 기존의 "이벤트마다 동기 send().get() + 개별 트랜잭션"
 * 직렬 처리 대비, Kafka 라운드트립이 병렬화되고 DB 쓰기가 JDBC 배치로 묶여 처리량이 크게 오른다.
 *
 * <p>Kafka 발행(네트워크 대기)은 트랜잭션 밖에서 수행해 DB 커넥션 점유 시간을 최소화하고,
 * 상태 갱신(PUBLISHED/FAILED)만 짧은 배치 트랜잭션으로 반영한다.
 *
 * <p>재시도/ DLQ 의미는 기존과 동일: 실패 시 {@link OutboxEvent#markFailed} 로 retryCount 를 올리고,
 * 한계(10회) 초과로 FAILED 전이되는 순간 정확히 한 번 DLQ 로 발행한다. 재시도가 필요한(여전히 PENDING)
 * 행은 claim 리스를 해제해 다음 주기에 곧바로 다시 잡히게 한다.
 */
@Service
public class OutboxBatchEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchEventPublisher.class);
    /** 배치 전체 발행 대기 상한 — 모든 send 가 in-flight 라 N배가 아닌 1배 수준의 벽시계 시간. */
    private static final long AWAIT_TIMEOUT_SEC = 30;

    private final PublishExternalEventPort publishExternalEventPort;
    private final PublishDlqEventPort publishDlqEventPort;
    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ClaimOutboxEventPort claimOutboxEventPort;
    private final Timer batchTimer;
    private final Counter dlqCounter;

    public OutboxBatchEventPublisher(PublishExternalEventPort publishExternalEventPort,
                                     PublishDlqEventPort publishDlqEventPort,
                                     SaveOutboxEventPort saveOutboxEventPort,
                                     ClaimOutboxEventPort claimOutboxEventPort,
                                     MeterRegistry meterRegistry) {
        this.publishExternalEventPort = publishExternalEventPort;
        this.publishDlqEventPort = publishDlqEventPort;
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.claimOutboxEventPort = claimOutboxEventPort;
        this.batchTimer = Timer.builder("outbox.publish.batch.duration")
                .description("outbox 배치 발행(dispatch+await+finalize) 처리 시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.dlqCounter = Counter.builder("outbox.dlq.published")
                .description("DLQ 로 발행된 누적 이벤트 수 (재시도 한계 초과)")
                .register(meterRegistry);
    }

    /**
     * @return PublishOutcome — 성공/실패 건수
     */
    public PublishOutcome publishBatch(List<OutboxEvent> events) {
        if (events.isEmpty()) {
            return new PublishOutcome(0, 0);
        }
        return batchTimer.record(() -> doPublishBatch(events));
    }

    private PublishOutcome doPublishBatch(List<OutboxEvent> events) {
        // 1) 전부 비동기 dispatch — 프로듀서가 in-flight 로 묶어 보낸다.
        Map<OutboxEvent, CompletableFuture<Void>> inflight = new LinkedHashMap<>();
        for (OutboxEvent event : events) {
            try {
                inflight.put(event, publishExternalEventPort.publishAsync(event));
            } catch (RuntimeException e) {
                inflight.put(event, CompletableFuture.failedFuture(e));
            }
        }

        // 2) 결과 수거 — 모두 in-flight 이므로 합산 대기 시간은 1배 수준.
        List<OutboxEvent> retryIds = new ArrayList<>();
        int published = 0;
        int failed = 0;
        for (Map.Entry<OutboxEvent, CompletableFuture<Void>> entry : inflight.entrySet()) {
            OutboxEvent event = entry.getKey();
            String error = awaitError(entry.getValue());
            if (error == null) {
                event.markPublished();
                published++;
            } else {
                failed++;
                event.markFailed(error);
                if (event.isFailed()) {
                    publishToDlqQuietly(event);
                } else {
                    retryIds.add(event);   // 여전히 PENDING → 리스 해제 대상
                }
            }
        }

        // 3) 상태 일괄 영속 (짧은 배치 트랜잭션)
        saveOutboxEventPort.saveAll(events);

        // 4) 재시도 대상 리스 해제 → 다음 주기 즉시 재클레임
        if (!retryIds.isEmpty()) {
            claimOutboxEventPort.releaseClaim(retryIds.stream().map(OutboxEvent::getId).toList());
        }

        return new PublishOutcome(published, failed);
    }

    /** 발행 결과를 기다려 실패 사유를 반환. 성공이면 null. */
    private String awaitError(CompletableFuture<Void> future) {
        try {
            future.get(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "publish interrupted";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return cause.getMessage() != null ? cause.getMessage() : cause.toString();
        } catch (TimeoutException e) {
            return "publish timeout after " + AWAIT_TIMEOUT_SEC + "s";
        }
    }

    private void publishToDlqQuietly(OutboxEvent event) {
        try {
            publishDlqEventPort.publishToDlq(event);
            dlqCounter.increment();
        } catch (RuntimeException e) {
            // DLQ 발행 실패가 배치 finalize 를 막지 않게 한다 — 이벤트는 FAILED 로 남아 알람/콘솔에서 회수.
            log.error("DLQ publish failed for eventId={}, event stays FAILED. error={}",
                    event.getEventId(), e.getMessage());
        }
    }

    public record PublishOutcome(int published, int failed) { }
}
