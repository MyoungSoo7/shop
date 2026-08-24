package github.lms.lemuel.operation.signal.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.signal.application.port.out.UpsertMetricBucketPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SignalRecordingServiceTest {

    @Mock
    UpsertMetricBucketPort upsertPort;

    private SignalRecordingService service;

    @BeforeEach
    void setUp() {
        OpsProperties properties = new OpsProperties();
        properties.getSignal().setBucketSeconds(300);
        service = new SignalRecordingService(upsertPort, properties);
    }

    @Test
    void recordEvent_는_5분_버킷으로_정렬해_incrementEvent_에_위임한다() {
        service.recordEvent("payment", false, Instant.parse("2026-07-07T06:03:22Z"));

        verify(upsertPort).incrementEvent("payment", Instant.parse("2026-07-07T06:00:00Z"), false);
    }

    @Test
    void recordEvent_는_signal_플래그를_그대로_전달한다() {
        service.recordEvent("settlement", true, Instant.parse("2026-07-07T06:07:00Z"));

        verify(upsertPort).incrementEvent("settlement", Instant.parse("2026-07-07T06:05:00Z"), true);
    }

    @Test
    void recordGauge_는_5분_버킷으로_정렬해_accumulateGauge_에_위임한다() {
        service.recordGauge("kafka.lag.max", 320.0, Instant.parse("2026-07-07T06:09:59Z"));

        verify(upsertPort).accumulateGauge("kafka.lag.max", Instant.parse("2026-07-07T06:05:00Z"), 320.0);
    }
}
