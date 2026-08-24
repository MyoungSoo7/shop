package github.lms.lemuel.operation.signal.adapter.out.prometheus;

import github.lms.lemuel.operation.signal.application.port.out.MetricSourcePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.OptionalDouble;

/**
 * Prometheus 폴링 비활성 시 주입되는 no-op 소스 — 항상 empty.
 *
 * <p>{@link github.lms.lemuel.operation.signal.application.service.MetricPollingService} 가
 * MetricSourcePort 를 항상 주입받을 수 있게 해 컨텍스트 로드를 보장한다 (로컬/테스트에서 폴러는
 * 스케줄러 미기동이라 실제 호출되지 않는다). shared-common 의 Kafka/NoOp 발행자 토글과 동일 패턴.
 */
@Component
@ConditionalOnProperty(name = "app.ops.prometheus.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpMetricSourceAdapter implements MetricSourcePort {

    @Override
    public OptionalDouble queryInstant(String promQl) {
        return OptionalDouble.empty();
    }
}
