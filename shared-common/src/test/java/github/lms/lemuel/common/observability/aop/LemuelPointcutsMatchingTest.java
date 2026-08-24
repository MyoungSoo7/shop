package github.lms.lemuel.common.observability.aop;

import github.lms.lemuel.common.config.observability.MdcKeys;
import github.lms.lemuel.tracefixture.adapter.in.batch.SampleBatchAdapter;
import github.lms.lemuel.tracefixture.adapter.in.kafka.SampleKafkaConsumer;
import github.lms.lemuel.tracefixture.adapter.in.web.SampleWebAdapter;
import github.lms.lemuel.tracefixture.application.service.SampleAppService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LemuelPointcuts#traceable()} 이 실제 패키지 컨벤션과 매칭되는지 검증한다.
 *
 * <p>{@link MethodTraceAspectTest} 는 포인트컷을 테스트 전용으로 재정의해 어드바이스 본문만
 * 검증하므로, 여기서는 재정의 없이 <b>실제 Aspect 그대로</b> 프록시를 만들어
 * 포인트컷 표현식 자체가 각 레이어 픽스처에 적용되는지 본다.
 */
class LemuelPointcutsMatchingTest {

    private <T> T realAspectProxy(T target, MeterRegistry registry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new MethodTraceAspect(new ObservabilityAopProperties(), provider));
        return factory.getProxy();
    }

    @Test
    void batch_adapter_is_traceable_with_batch_layer_tag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleBatchAdapter proxy = realAspectProxy(new SampleBatchAdapter(), registry);

        assertThat(proxy.runJob()).isEqualTo("done");

        Timer timer = registry.find("lemuel.method.execution")
                .tag("layer", "batch")
                .tag("class", "SampleBatchAdapter")
                .tag("method", "runJob")
                .tag("outcome", "success")
                .timer();
        assertThat(timer)
                .as("adapter.in.batch 스케줄러/폴러가 traceable() 에 포함되어야 한다")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void web_adapter_is_traceable_with_web_layer_tag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleWebAdapter proxy = realAspectProxy(new SampleWebAdapter(), registry);

        assertThat(proxy.handleRequest()).isEqualTo("ok");

        Timer timer = registry.find("lemuel.method.execution")
                .tag("layer", "web")
                .tag("class", "SampleWebAdapter")
                .tag("method", "handleRequest")
                .tag("outcome", "success")
                .timer();
        assertThat(timer)
                .as("adapter.in.web 컨트롤러가 traceable() 에 포함되어야 한다")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void kafka_consumer_is_traceable_and_gets_execution_scoped_trace_id() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleKafkaConsumer proxy = realAspectProxy(new SampleKafkaConsumer(), registry);

        MDC.remove(MdcKeys.TRACE_ID);
        AtomicReference<String> observed = new AtomicReference<>();
        try {
            assertThat(proxy.consume(observed)).isEqualTo("consumed");
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
        }

        Timer timer = registry.find("lemuel.method.execution")
                .tag("layer", "kafka")
                .tag("class", "SampleKafkaConsumer")
                .tag("method", "consume")
                .tag("outcome", "success")
                .timer();
        assertThat(timer)
                .as("adapter.in.kafka 컨슈머가 traceable() 에 포함되어야 한다")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);

        // 서블릿 필터 밖(Kafka 리스너 스레드)에서도 실행 단위 traceId 가 붙는다 —
        // 이 보장은 kafka 포인트컷이 실제로 매칭될 때만 성립하므로 여기서 함께 못박는다.
        assertThat(observed.get())
                .as("Kafka 컨슈머 진입 시 MDC traceId 가 부여되어야 한다")
                .isNotBlank();
        // 스레드 풀 재사용 대비 — 호출이 끝나면 제거된다.
        assertThat(MDC.get(MdcKeys.TRACE_ID)).isNull();
    }

    @Test
    void application_service_stays_traceable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleAppService proxy = realAspectProxy(new SampleAppService(), registry);

        assertThat(proxy.handle()).isEqualTo("handled");

        Timer timer = registry.find("lemuel.method.execution")
                .tag("layer", "service")
                .tag("class", "SampleAppService")
                .tag("outcome", "success")
                .timer();
        assertThat(timer)
                .as("기존 application.service 매칭이 회귀하면 안 된다")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
