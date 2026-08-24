package github.lms.lemuel.payment.adapter.out.monitoring;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RefundMetricsTest {

    @Test
    @DisplayName("환불 요청/완료/멱등키재사용 카운터가 증가한다")
    void incrementCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundMetrics metrics = new RefundMetrics(registry);

        metrics.incrementRefundRequest();
        metrics.incrementRefundCompleted();
        metrics.incrementIdempotencyKeyReuse();

        assertThat(registry.get("refund.requests").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("refund.completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("refund.idempotency_key_reuse").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("환불 실패 카운터는 reason 태그와 함께 등록된다")
    void incrementRefundFailed_tagsReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundMetrics metrics = new RefundMetrics(registry);

        metrics.incrementRefundFailed("PG_TIMEOUT");

        assertThat(registry.get("refund.failed").tag("reason", "PG_TIMEOUT").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("환불 금액 분포를 기록한다")
    void recordRefundAmount_recordsDistribution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundMetrics metrics = new RefundMetrics(registry);

        metrics.recordRefundAmount(new BigDecimal("15000"));

        assertThat(registry.get("refund.amount").summary().totalAmount()).isEqualTo(15000.0);
    }

    @Test
    @DisplayName("처리 시간 타이머 시작/기록이 duration 카운트를 증가시킨다")
    void timer_startAndStop_recordsDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundMetrics metrics = new RefundMetrics(registry);

        Timer.Sample sample = metrics.startTimer();
        metrics.stopAndRecordProcessing(sample);

        assertThat(registry.get("refund.processing.duration").timer().count()).isEqualTo(1L);
    }
}
