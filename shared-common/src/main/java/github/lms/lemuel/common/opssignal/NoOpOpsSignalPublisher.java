package github.lms.lemuel.common.opssignal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka 비활성(app.kafka.enabled=false) 시 주입되는 no-op 발행자.
 *
 * <p>단위 테스트·Kafka 없는 환경에서 실패 지점의 {@code opsSignalPort.emit(...)} 호출이
 * 안전하게 무시되도록 한다. shared-common 의 Kafka/NoOp 발행자 토글과 동일 패턴.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOpsSignalPublisher implements OpsSignalPort {

    @Override
    public void emit(OpsSignal signal) {
        // no-op
    }

    @Override
    public void emit(OpsSignalCategory category, String entityType, String entityId, Map<String, Object> attributes) {
        // no-op
    }
}
