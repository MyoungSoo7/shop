package github.lms.lemuel.common.autoconfigure;

import github.lms.lemuel.common.observability.aop.AopObservabilityConfig;
import github.lms.lemuel.common.observability.aop.MethodTraceAspect;
import github.lms.lemuel.common.observability.aop.ObservabilityAopProperties;
import github.lms.lemuel.common.observability.aop.TransactionTraceAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 메서드 추적·트랜잭션 경계 추적 Aspect 자동 구성.
 *
 * <p>스캔 범위를 좁힌 서비스도 별도 배선 없이 동일한 관측 수준을 갖도록,
 * {@link AopObservabilityConfig}(스캔 경로)와 같은 빈을 자동 구성으로도 제공한다.
 *
 * <p><b>이중 등록 방지</b> — 그 설정 클래스가 이미 빈으로 있으면(=루트 스캔 서비스) 이 자동 구성
 * 전체가 물러난다. 조건 대상이 Aspect 타입이 아니라 <b>설정 클래스</b>인 이유는
 * {@code JacksonCompatAutoConfiguration} 에 적어둔 것과 같다 — 스캔된 {@code @Bean} 정의는 다음 파싱
 * 라운드에 등록돼, 타입 조건으로는 같은 빈 이름 충돌
 * ({@code BeanDefinitionOverrideException})을 막지 못한다.
 *
 * <p>프록시 생성 자체는 부트의 {@link AopAutoConfiguration}(기본
 * {@code spring.aop.proxy-target-class=true}, 즉 CGLIB)이 맡으므로 여기서
 * {@code @EnableAspectJAutoProxy} 를 다시 켜지 않는다. 순서만 그 뒤로 둔다.
 *
 * <p>{@code app.observability.aop.enabled=false} 로 끌 수 있다(기본 on) —
 * 스캔 경로와 동일한 스위치다.
 */
@AutoConfiguration(after = AopAutoConfiguration.class)
@ConditionalOnClass(Aspect.class)
@ConditionalOnMissingBean(AopObservabilityConfig.class)
@EnableConfigurationProperties(ObservabilityAopProperties.class)
@ConditionalOnProperty(prefix = "app.observability.aop", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObservabilityAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MethodTraceAspect.class)
    public MethodTraceAspect methodTraceAspect(ObservabilityAopProperties properties,
                                               ObjectProvider<MeterRegistry> meterRegistry) {
        return new MethodTraceAspect(properties, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionTraceAspect.class)
    public TransactionTraceAspect transactionTraceAspect() {
        return new TransactionTraceAspect();
    }
}
