package github.lms.lemuel.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.observability.aop.AopObservabilityConfig;
import github.lms.lemuel.common.observability.aop.MethodTraceAspect;
import github.lms.lemuel.common.observability.aop.TransactionTraceAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 구성이 <b>스캔 경로와 충돌하지 않는지</b>를 검증한다.
 *
 * <p>핵심 위험은 이중 등록이다 — 루트를 스캔하는 서비스는 이미 {@link JacksonCompatConfig},
 * {@link AopObservabilityConfig} 를 빈으로 잡고 있으므로, 자동 구성이 같은 빈을 또 만들면
 * 매퍼가 둘이 되고 Aspect 가 두 번 감싼다(로그·메트릭 중복). {@code @ConditionalOnMissingBean} 이
 * 실제로 물러나는지 여기서 못박는다.
 */
class SharedCommonAutoConfigurationBehaviorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonCompatAutoConfiguration.class,
                    ObservabilityAopAutoConfiguration.class));

    // ── Jackson 호환 매퍼 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("스캔이 없는 서비스: 자동 구성이 Jackson2 매퍼를 채운다")
    void fillsJacksonMappersWhenAbsent() {
        runner.run(context -> {
            assertThat(context).hasBean("jacksonLegacyObjectMapper").hasBean("outboxObjectMapper");
            // 범용 + outbox 두 개, 그 이상은 아니다.
            assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(2);
        });
    }

    @Test
    @DisplayName("스캔으로 JacksonCompatConfig 를 이미 잡은 서비스: 자동 구성 전체가 물러난다")
    void backsOffWhenScannedConfigAlreadyPresent() {
        runner.withUserConfiguration(JacksonCompatConfig.class).run(context -> {
            // 자동 구성이 통째로 빠져야 한다 — 남아 있으면 같은 빈 이름으로 등록을 시도해
            // BeanDefinitionOverrideException 으로 서비스가 기동조차 못 한다(account-service 실측 회귀).
            assertThat(context).doesNotHaveBean(JacksonCompatAutoConfiguration.class);
            // 스캔 정의 2개(범용 + outbox)만 존재한다.
            assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(2);
            assertThat(context).hasBean("jacksonLegacyObjectMapper").hasBean("outboxObjectMapper");
        });
    }

    @Test
    @DisplayName("app.jackson.compat.enabled=false 면 자동 구성이 뜨지 않는다")
    void jacksonCompatCanBeDisabled() {
        runner.withPropertyValues("app.jackson.compat.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("jacksonLegacyObjectMapper"));
    }

    // ── 관측 Aspect ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스캔이 없는 서비스: 자동 구성이 추적 Aspect 2종을 채운다")
    void fillsObservabilityAspectsWhenAbsent() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(MethodTraceAspect.class)
                .hasSingleBean(TransactionTraceAspect.class));
    }

    @Test
    @DisplayName("스캔으로 AopObservabilityConfig 를 이미 잡은 서비스: 자동 구성이 물러나 Aspect 가 중복되지 않는다")
    void doesNotDoubleRegisterAspects() {
        runner.withUserConfiguration(AopObservabilityConfig.class).run(context -> {
            assertThat(context).doesNotHaveBean(ObservabilityAopAutoConfiguration.class);
            assertThat(context.getBeansOfType(MethodTraceAspect.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionTraceAspect.class)).hasSize(1);
        });
    }

    @Test
    @DisplayName("app.observability.aop.enabled=false 면 Aspect 가 뜨지 않는다")
    void observabilityAopCanBeDisabled() {
        runner.withPropertyValues("app.observability.aop.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MethodTraceAspect.class)
                        .doesNotHaveBean(TransactionTraceAspect.class));
    }
}
