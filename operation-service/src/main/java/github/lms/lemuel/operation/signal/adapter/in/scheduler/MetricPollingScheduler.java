package github.lms.lemuel.operation.signal.adapter.in.scheduler;

import github.lms.lemuel.operation.signal.application.port.in.PollMetricsUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prometheus 폴링 스케줄러 — 설정 주기마다 인스턴트 쿼리를 실행해 게이지 버킷을 채운다.
 *
 * <p>{@code app.ops.prometheus.enabled=true} 일 때만 빈이 생성된다 — 로컬/테스트에서는
 * 미기동이라 Prometheus 없이도 안전하다. 실제 쿼리·오류 격리는 {@link PollMetricsUseCase} 가 맡는다.
 */
@Component
@ConditionalOnProperty(name = "app.ops.prometheus.enabled", havingValue = "true")
public class MetricPollingScheduler {

    private final PollMetricsUseCase pollMetricsUseCase;

    public MetricPollingScheduler(PollMetricsUseCase pollMetricsUseCase) {
        this.pollMetricsUseCase = pollMetricsUseCase;
    }

    /** 고정 지연 폴링. 직전 폴링 종료 후 interval 만큼 쉬고 다시 — 폴링이 밀려도 겹치지 않는다. */
    @Scheduled(fixedDelayString = "${app.ops.prometheus.poll-interval-ms:60000}")
    public void poll() {
        pollMetricsUseCase.pollOnce();
    }
}
