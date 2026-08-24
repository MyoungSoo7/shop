package github.lms.lemuel.common.observability.aop;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 로깅/성능/트랜잭션 추적 Aspect 등록 설정.
 *
 * <p>{@code app.observability.aop.enabled=false} 로 전체를 끌 수 있다(기본 on).
 * AuditAopConfig 에서 이미 {@code @EnableAspectJAutoProxy} 를 켰지만, 이 모듈만 단독으로
 * 임포트돼도 동작하도록 여기서도 명시한다(중복 선언은 무해).
 */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(ObservabilityAopProperties.class)
@ConditionalOnProperty(prefix = "app.observability.aop", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AopObservabilityConfig {

    @Bean
    public MethodTraceAspect methodTraceAspect(ObservabilityAopProperties properties,
                                               ObjectProvider<MeterRegistry> meterRegistry) {
        return new MethodTraceAspect(properties, meterRegistry);
    }

    @Bean
    public TransactionTraceAspect transactionTraceAspect() {
        return new TransactionTraceAspect();
    }
}
