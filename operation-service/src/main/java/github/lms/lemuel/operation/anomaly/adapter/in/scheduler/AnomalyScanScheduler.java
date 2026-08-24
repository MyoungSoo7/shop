package github.lms.lemuel.operation.anomaly.adapter.in.scheduler;

import github.lms.lemuel.operation.anomaly.application.port.in.DetectAnomaliesUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이상 탐지 스케줄러 — 버킷 폭(5분)과 같은 주기로 직전 마감 버킷을 판정한다.
 *
 * <p>{@code app.ops.anomaly.enabled=true} 일 때만 빈이 생성된다(로컬/테스트 기본 off — Prometheus
 * 폴러와 동일 패턴). fixedDelay 라 판정이 밀려도 겹치지 않으며(단일 스레드), 실제 판정·오류 격리는
 * {@link DetectAnomaliesUseCase} 가 담당한다(한 metric 실패가 다른 metric·다음 스캔을 막지 않음).
 */
@Component
@ConditionalOnProperty(name = "app.ops.anomaly.enabled", havingValue = "true")
public class AnomalyScanScheduler {

    private final DetectAnomaliesUseCase detectAnomaliesUseCase;

    public AnomalyScanScheduler(DetectAnomaliesUseCase detectAnomaliesUseCase) {
        this.detectAnomaliesUseCase = detectAnomaliesUseCase;
    }

    @Scheduled(fixedDelayString = "${app.ops.anomaly.scan-interval-ms:300000}")
    public void scan() {
        detectAnomaliesUseCase.detectOnce();
    }
}
