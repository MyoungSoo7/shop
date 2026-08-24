package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.in.OutboxAdminUseCase;
import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Outbox DLQ 운영자 액션 서비스.
 *
 * <p>{@link OutboxAdminUseCase} 의 표준 구현. 모든 액션은 ERROR 레벨로 남겨
 * Audit log 로 자동 수집되며, Prometheus 카운터로 노출된다 ({@code outbox.admin.*}).
 *
 * <p>운영자 ID 는 컨트롤러에서 SecurityContext 로부터 추출해 전달한다.
 */
@Service
@Transactional
public class OutboxAdminService implements OutboxAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminService.class);

    private final LoadOutboxEventPort loadOutboxEventPort;
    private final SaveOutboxEventPort saveOutboxEventPort;
    private final Counter retryCounter;
    private final Counter skipCounter;

    public OutboxAdminService(LoadOutboxEventPort loadOutboxEventPort,
                              SaveOutboxEventPort saveOutboxEventPort,
                              MeterRegistry meterRegistry) {
        this.loadOutboxEventPort = loadOutboxEventPort;
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.retryCounter = Counter.builder("outbox.admin.retry")
                .description("운영자가 DLQ 콘솔에서 재처리한 이벤트 수")
                .register(meterRegistry);
        this.skipCounter = Counter.builder("outbox.admin.skip")
                .description("운영자가 DLQ 콘솔에서 스킵한 이벤트 수")
                .register(meterRegistry);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEvent> listFailed(int offset, int limit) {
        if (limit <= 0 || limit > 100) {
            limit = 20;
        }
        if (offset < 0) {
            offset = 0;
        }
        return loadOutboxEventPort.findFailed(offset, limit);
    }

    @Override
    public OutboxEvent retry(UUID eventId) {
        OutboxEvent event = loadOutboxEventPort.findByEventId(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));

        event.requeue();
        OutboxEvent saved = saveOutboxEventPort.save(event);
        retryCounter.increment();
        log.warn("[OutboxAdmin] DLQ retry by operator. eventId={}, type={}, aggregate={}/{}",
                eventId, event.getEventType(), event.getAggregateType(), event.getAggregateId());
        return saved;
    }

    @Override
    public OutboxEvent skip(UUID eventId, String reason, String operatorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("스킵 사유는 필수 (감사 추적용)");
        }
        OutboxEvent event = loadOutboxEventPort.findByEventId(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));

        String enriched = "operator=" + (operatorId == null ? "unknown" : operatorId) + ", reason=" + reason;
        event.skip(enriched);
        OutboxEvent saved = saveOutboxEventPort.save(event);
        skipCounter.increment();
        log.warn("[OutboxAdmin] DLQ skip by operator. eventId={}, operator={}, reason={}",
                eventId, operatorId, reason);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public long failedCount() {
        return loadOutboxEventPort.countFailed();
    }
}
