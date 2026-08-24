package github.lms.lemuel.common.observability.aop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AOP 기반 관측(로깅/성능/트랜잭션 추적) 동작을 제어하는 설정.
 *
 * <pre>{@code
 * app:
 *   observability:
 *     aop:
 *       enabled: true            # 전체 on/off
 *       slow-threshold-ms: 500   # 이 시간을 초과하면 WARN 으로 승격
 *       log-args: false          # true 면 메서드 인자 값을 로그에 남김 (PII 주의 — 기본 off)
 *       max-arg-length: 200      # 인자 toString 최대 길이 (초과 시 절단)
 * }</pre>
 */
@ConfigurationProperties(prefix = "app.observability.aop")
public class ObservabilityAopProperties {

    /** AOP 관측 전체 활성화 여부. */
    private boolean enabled = true;

    /** 이 임계값(ms)을 초과한 메서드는 DEBUG 대신 WARN 으로 로깅. */
    private long slowThresholdMs = 500;

    /**
     * 메서드 인자 값을 로그에 포함할지 여부.
     * PII 노출 위험이 있으므로 기본은 false (시그니처와 소요시간만 기록).
     */
    private boolean logArgs = false;

    /** 인자 toString 의 최대 길이. 초과분은 잘라낸다. */
    private int maxArgLength = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    public void setSlowThresholdMs(long slowThresholdMs) {
        this.slowThresholdMs = slowThresholdMs;
    }

    public boolean isLogArgs() {
        return logArgs;
    }

    public void setLogArgs(boolean logArgs) {
        this.logArgs = logArgs;
    }

    public int getMaxArgLength() {
        return maxArgLength;
    }

    public void setMaxArgLength(int maxArgLength) {
        this.maxArgLength = maxArgLength;
    }
}
