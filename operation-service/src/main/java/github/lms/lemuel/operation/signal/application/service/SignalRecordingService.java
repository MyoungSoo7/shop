package github.lms.lemuel.operation.signal.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import github.lms.lemuel.operation.signal.application.port.out.UpsertMetricBucketPort;
import github.lms.lemuel.operation.signal.domain.BucketWindow;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 신호 1건을 5분 버킷으로 정렬해 UPSERT 누적한다.
 *
 * <p>트랜잭션을 두지 않는다 — 단일 원자 UPSERT 라 컨슈머의 @Transactional(있으면) 경계에
 * 그대로 얹히거나, 폴러의 독립 호출로도 안전하다. 통계 버킷이라 at-least-once 중복은 허용
 * (드문 재전송이 5분 카운트를 거의 흔들지 않음 — Phase 3 는 상대임계+z-score 로 노이즈에 강건).
 */
@Service
public class SignalRecordingService implements RecordSignalUseCase {

    private final UpsertMetricBucketPort upsertPort;
    private final int bucketSeconds;

    public SignalRecordingService(UpsertMetricBucketPort upsertPort, OpsProperties properties) {
        this.upsertPort = upsertPort;
        this.bucketSeconds = properties.getSignal().getBucketSeconds();
    }

    @Override
    public void recordEvent(String metricKey, boolean signal, Instant occurredAt) {
        Instant bucket = BucketWindow.floor(occurredAt, bucketSeconds);
        upsertPort.incrementEvent(metricKey, bucket, signal);
    }

    @Override
    public void recordGauge(String metricKey, double value, Instant observedAt) {
        Instant bucket = BucketWindow.floor(observedAt, bucketSeconds);
        upsertPort.accumulateGauge(metricKey, bucket, value);
    }
}
