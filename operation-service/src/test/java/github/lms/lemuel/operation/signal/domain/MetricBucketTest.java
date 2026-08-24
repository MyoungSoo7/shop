package github.lms.lemuel.operation.signal.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricBucketTest {

    private MetricBucket counter(long total, long signal) {
        return new MetricBucket("payment", Instant.EPOCH, total, signal, 0, null, 0);
    }

    private MetricBucket gauge(double sum, Double max, long samples) {
        return new MetricBucket("kafka.lag.max", Instant.EPOCH, 0, 0, sum, max, samples);
    }

    @Test
    void failureRate_는_countSignal_나누기_countTotal_이다() {
        assertThat(counter(20, 3).failureRate()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void 분모_0_이면_failureRate_는_0() {
        assertThat(counter(0, 0).failureRate()).isZero();
    }

    @Test
    void average_는_valueSum_나누기_sampleCount_이다() {
        assertThat(gauge(1200.0, 800.0, 4).average()).isCloseTo(300.0, within(1e-9));
    }

    @Test
    void 표본_0_이면_average_는_0() {
        assertThat(gauge(0, null, 0).average()).isZero();
    }
}
