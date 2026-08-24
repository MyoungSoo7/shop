package github.lms.lemuel.common.observability.aop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import github.lms.lemuel.common.config.observability.MdcKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MethodTraceAspect} 가 대상 메서드를 감싸 실행시간을 재고 Micrometer Timer 에
 * 성공/실패 결과를 기록하는지 검증한다. Spring 컨텍스트 없이 AspectJ 프록시로 단위 검증.
 */
class MethodTraceAspectTest {

    // DEBUG 를 켜야 진입/종료 debug 로그 + argsSuffix/renderArg 경로가 실행된다.
    private static Level originalLevel;

    @BeforeAll
    static void enableDebug() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodTraceAspect.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterAll
    static void restoreLevel() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodTraceAspect.class)).setLevel(originalLevel);
    }

    /** 포인트컷(applicationService) 매칭을 위한 가짜 서비스 — 패키지 컨벤션을 모사. */
    static class SampleService {
        String greet(String name) {
            return "hi " + name;
        }

        void boom() {
            throw new IllegalStateException("kaboom");
        }

        String ping() {
            return "pong";
        }
    }

    /** 중첩 호출(깊이 2)을 만들기 위한 바깥 서비스 — 안쪽은 프록시된 SampleService 를 부른다. */
    static class OuterService {
        private final SampleService inner;

        OuterService(SampleService inner) {
            this.inner = inner;
        }

        String call() {
            return inner.greet("nested");
        }

        String callFailing() {
            inner.boom();
            return "unreachable";
        }
    }

    /** 어드바이스 안쪽에서 관측된 MDC traceId 를 밖으로 흘려주는 프로브. */
    static class ProbeService {
        private final AtomicReference<String> seen;

        ProbeService(AtomicReference<String> seen) {
            this.seen = seen;
        }

        String observe() {
            seen.set(MDC.get(MdcKeys.TRACE_ID));
            return "observed";
        }
    }

    private SampleService proxyWith(MeterRegistry registry) {
        return proxyWith(registry, new ObservabilityAopProperties());
    }

    private SampleService proxyWith(MeterRegistry registry, ObservabilityAopProperties props) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(new TestAspect(props, provider));
        return factory.getProxy();
    }

    /** SampleService 를 직접 겨냥하는 포인트컷으로 재정의한 테스트 전용 Aspect. */
    @org.aspectj.lang.annotation.Aspect
    static class TestAspect extends MethodTraceAspect {
        TestAspect(ObservabilityAopProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
            super(properties, meterRegistry);
        }

        @org.aspectj.lang.annotation.Around(
                "execution(* github.lms.lemuel.common.observability.aop.MethodTraceAspectTest.*Service.*(..))")
        public Object around(org.aspectj.lang.ProceedingJoinPoint pjp) throws Throwable {
            return trace(pjp);
        }
    }

    /** 바깥 서비스 → 안쪽 서비스 모두 어드바이스가 걸린 프록시 체인. */
    private OuterService nestedProxy(ObservabilityAopProperties props) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());

        AspectJProxyFactory innerFactory = new AspectJProxyFactory(new SampleService());
        innerFactory.addAspect(new TestAspect(props, provider));
        SampleService inner = innerFactory.getProxy();

        AspectJProxyFactory outerFactory = new AspectJProxyFactory(new OuterService(inner));
        outerFactory.addAspect(new TestAspect(props, provider));
        return outerFactory.getProxy();
    }

    /** MethodTraceAspect 로거에 붙여 로그 라인을 수집하는 어펜더. */
    private ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodTraceAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodTraceAspect.class))
                .detachAppender(appender);
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void nested_calls_are_rendered_with_call_depth_prefix() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            OuterService outer = nestedProxy(new ObservabilityAopProperties());

            assertThat(outer.call()).isEqualTo("hi nested");

            List<String> lines = messages(appender);
            // 최상위(depth 1)는 들여쓰기 없이, 중첩(depth 2)은 계단식 들여쓰기가 붙는다.
            assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("→ ").contains("OuterService.call"));
            assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("|   → ").contains("SampleService.greet"));
            assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("|   ← ").contains("SampleService.greet"));
            assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("← ").contains("OuterService.call"));
        } finally {
            detach(appender);
        }
    }

    @Test
    void nested_exception_is_rendered_with_depth_prefix() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            OuterService outer = nestedProxy(new ObservabilityAopProperties());

            assertThatThrownBy(outer::callFailing).isInstanceOf(IllegalStateException.class);

            assertThat(messages(appender))
                    .anySatisfy(l -> assertThat(l).startsWith("|   ✗ ").contains("SampleService.boom"));
        } finally {
            detach(appender);
        }
    }

    @Test
    void root_entry_attaches_trace_id_and_removes_it_afterwards() {
        MDC.remove(MdcKeys.TRACE_ID);
        try {
            AtomicReference<String> seenInside = new AtomicReference<>();
            @SuppressWarnings("unchecked")
            ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());

            AspectJProxyFactory factory = new AspectJProxyFactory(new ProbeService(seenInside));
            factory.addAspect(new TestAspect(new ObservabilityAopProperties(), provider));
            ProbeService service = factory.getProxy();

            assertThat(service.observe()).isEqualTo("observed");

            // 웹 필터 밖(Kafka·스케줄러)에서도 실행 단위 traceId 가 부여되고,
            assertThat(seenInside.get()).isNotBlank();
            // 스레드 풀 재사용 대비 — 호출이 끝나면 반드시 제거된다.
            assertThat(MDC.get(MdcKeys.TRACE_ID)).isNull();
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
        }
    }

    @Test
    void existing_trace_id_is_preserved_and_not_removed() {
        MDC.put(MdcKeys.TRACE_ID, "upstream-trace");
        try {
            SampleService service = proxyWith(new SimpleMeterRegistry());

            assertThat(service.ping()).isEqualTo("pong");

            // 필터가 붙인 traceId 는 우리가 만든 게 아니므로 지우지 않는다(부착한 쪽이 지운다).
            assertThat(MDC.get(MdcKeys.TRACE_ID)).isEqualTo("upstream-trace");
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
        }
    }

    @Test
    void records_success_timer_and_returns_result() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleService service = proxyWith(registry);

        String result = service.greet("lemuel");

        assertThat(result).isEqualTo("hi lemuel");
        Timer timer = registry.find("lemuel.method.execution")
                .tag("method", "greet")
                .tag("outcome", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void records_error_timer_and_rethrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleService service = proxyWith(registry);

        assertThatThrownBy(service::boom).isInstanceOf(IllegalStateException.class);

        Timer timer = registry.find("lemuel.method.execution")
                .tag("method", "boom")
                .tag("outcome", "error")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void works_without_meter_registry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(new TestAspect(new ObservabilityAopProperties(), provider));
        SampleService service = factory.getProxy();

        // MeterRegistry 가 없어도 비즈니스 흐름은 정상 동작해야 한다.
        assertThat(Stream.of(service.greet("x")).findFirst()).contains("hi x");
    }

    @Test
    void slow_threshold_promotes_to_warn() {
        ObservabilityAopProperties props = new ObservabilityAopProperties();
        props.setSlowThresholdMs(0); // 모든 호출을 SLOW 로 간주 → WARN 승격 분기 커버
        SampleService service = proxyWith(new SimpleMeterRegistry(), props);

        assertThat(service.greet("slow")).isEqualTo("hi slow");
    }

    @Test
    void renders_args_when_logArgs_enabled() {
        ObservabilityAopProperties props = new ObservabilityAopProperties();
        props.setLogArgs(true);
        props.setMaxArgLength(2); // 인자 길이 초과 절단 분기 커버
        SampleService service = proxyWith(new SimpleMeterRegistry(), props);

        // 인자 있는 호출(절단) + 인자 없는 호출(빈 괄호) 두 분기
        assertThat(service.greet("abcdef")).isEqualTo("hi abcdef");
        assertThat(service.ping()).isEqualTo("pong");
    }
}
