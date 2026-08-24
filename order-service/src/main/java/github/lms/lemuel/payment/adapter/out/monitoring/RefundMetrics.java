package github.lms.lemuel.payment.adapter.out.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Refund Metrics (Outbound Adapter - Monitoring)
 * 환불 작업 메트릭 수집 및 외부 모니터링 시스템 연동
 *
 * Clean Architecture:
 * - Micrometer 메트릭은 외부 모니터링 시스템(Prometheus, Grafana)으로 데이터를 내보내는 Outbound Adapter
 * - Payment bounded-context의 refund subdomain 메트릭 담당
 * - Application/Domain 레이어는 이 클래스를 직접 의존하지 않음 (필요시 port 정의)
 */
@Component
public class RefundMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter refundRequestCounter;
    private final Counter refundCompletedCounter;
    private final Counter idempotencyKeyReuseCounter;
    private final DistributionSummary refundAmountSummary;
    private final Timer refundProcessingTimer;

    public RefundMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.refundRequestCounter = Counter.builder("refund.requests")
                .description("Number of refund requests")
                .register(meterRegistry);

        this.refundCompletedCounter = Counter.builder("refund.completed")
                .description("Number of completed refunds")
                .register(meterRegistry);

        this.idempotencyKeyReuseCounter = Counter.builder("refund.idempotency_key_reuse")
                .description("Number of idempotency key reuses")
                .register(meterRegistry);

        this.refundAmountSummary = DistributionSummary.builder("refund.amount")
                .description("Distribution of refund amounts")
                .baseUnit("KRW")
                .register(meterRegistry);

        this.refundProcessingTimer = Timer.builder("refund.processing.duration")
                .description("Duration of refund processing")
                .register(meterRegistry);
    }

    public void incrementRefundRequest() {
        refundRequestCounter.increment();
    }

    public void incrementRefundCompleted() {
        refundCompletedCounter.increment();
    }

    public void incrementRefundFailed(String reason) {
        Counter.builder("refund.failed")
                .description("Number of failed refunds")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    public void incrementIdempotencyKeyReuse() {
        idempotencyKeyReuseCounter.increment();
    }

    public void recordRefundAmount(BigDecimal amount) {
        refundAmountSummary.record(amount.doubleValue());
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopAndRecordProcessing(Timer.Sample sample) {
        sample.stop(refundProcessingTimer);
    }
}
