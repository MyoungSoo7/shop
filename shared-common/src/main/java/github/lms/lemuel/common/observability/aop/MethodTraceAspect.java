package github.lms.lemuel.common.observability.aop;

import github.lms.lemuel.common.config.observability.MdcKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 로깅 + 성능 추적을 담당하는 핵심 Aspect.
 *
 * <p>{@link LemuelPointcuts#traceable()} (웹 어댑터 · 애플리케이션 서비스 · Kafka 컨슈머 · 배치 어댑터) 에 대해:
 * <ul>
 *   <li>진입 시 DEBUG 로그 ({@code → Layer Class.method})</li>
 *   <li>실행 시간 측정 후 종료 로그 ({@code ← ... (12ms)})</li>
 *   <li>{@code slow-threshold-ms} 초과 시 WARN 으로 승격</li>
 *   <li>예외 발생 시 ERROR 로그 + 소요시간</li>
 *   <li>Micrometer {@code lemuel.method.execution} Timer 에 layer/class/method/outcome 태그로 기록</li>
 *   <li><b>호출 깊이(depth)</b> 를 계단식 들여쓰기로 표현 ({@code |   |   }) — 어느 호출이 어느 호출
 *       안에서 일어났는지 로그만 보고 복원할 수 있다</li>
 * </ul>
 *
 * <p>가장 바깥에서 시간을 재야 내부(트랜잭션 포함) 전체가 포착되므로
 * {@link Order} 를 최우선으로 둔다. {@link AuditAspect}(LOWEST_PRECEDENCE-100) 보다 바깥.
 *
 * <p><b>실행 단위 traceId</b> — HTTP 요청은 {@code TraceIdFilter} 가 MDC 에 traceId 를 부착하지만,
 * Kafka 컨슈머·스케줄러·아웃박스 폴러는 서블릿 필터 밖이라 traceId 가 없었다. 그래서 최상위
 * 진입(depth 1)에서 MDC 에 traceId 가 없으면 여기서 만들어 붙이고, <b>같은 진입이 끝날 때 반드시
 * 제거</b>한다. 스레드 풀이 스레드를 재사용하므로 제거하지 않으면 다음 실행이 남의 traceId 를
 * 물려받는다(김영한 스프링 고급편 §3 "쓰레드 로컬 - 주의사항"과 같은 이유 — MDC 자체가 ThreadLocal 이다).
 * 깊이 카운터도 같은 이유로 0 이 되는 순간 {@code remove()} 한다.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MethodTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(MethodTraceAspect.class);
    private static final String TIMER_NAME = "lemuel.method.execution";

    /**
     * 스레드별 호출 깊이. 계단식 로그의 들여쓰기 근거이자 "최상위 진입인가" 판정 기준이다.
     * 깊이가 0 으로 돌아오면 즉시 {@code remove()} — 스레드 풀 재사용 시 값이 남지 않게 한다.
     */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    private final ObservabilityAopProperties properties;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public MethodTraceAspect(ObservabilityAopProperties properties,
                             ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Around("github.lms.lemuel.common.observability.aop.LemuelPointcuts.traceable()")
    public Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String layer = layerOf(signature.getDeclaringType().getName());
        String type = signature.getDeclaringType().getSimpleName();
        String method = signature.getName();

        int depth = ++DEPTH.get()[0];
        // 최상위 진입인데 traceId 가 없다 = 서블릿 필터를 거치지 않은 실행 단위(Kafka·스케줄러·배치).
        // 여기서 부여하고, 이 호출이 끝날 때 우리가 지운다(부착한 쪽이 지운다).
        boolean traceIdOwner = depth == 1 && MDC.get(MdcKeys.TRACE_ID) == null;
        if (traceIdOwner) {
            MDC.put(MdcKeys.TRACE_ID, UUID.randomUUID().toString());
        }

        if (log.isDebugEnabled()) {
            log.debug("{}→ [{}] {}.{}{}", prefix(depth), layer, type, method, argsSuffix(joinPoint));
        }

        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Throwable error) {
            outcome = "error";
            long elapsedMs = elapsedMs(startNanos);
            log.error("{}✗ [{}] {}.{} failed after {}ms — {}: {}",
                    prefix(depth), layer, type, method, elapsedMs,
                    error.getClass().getSimpleName(), error.getMessage());
            throw error;
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            record(layer, type, method, outcome, elapsedNanos);

            if ("success".equals(outcome)) {
                if (elapsedMs >= properties.getSlowThresholdMs()) {
                    log.warn("{}← [{}] {}.{} SLOW {}ms (threshold {}ms)",
                            prefix(depth), layer, type, method, elapsedMs, properties.getSlowThresholdMs());
                } else if (log.isDebugEnabled()) {
                    log.debug("{}← [{}] {}.{} {}ms", prefix(depth), layer, type, method, elapsedMs);
                }
            }
            releaseScope(traceIdOwner);
        }
    }

    /**
     * 깊이 카운터를 되돌리고, 최상위였다면 ThreadLocal·MDC 를 비운다.
     * 스레드 풀 재사용 환경에서 값이 남으면 다음 실행이 남의 컨텍스트를 물려받는다.
     */
    private static void releaseScope(boolean traceIdOwner) {
        int[] holder = DEPTH.get();
        if (--holder[0] <= 0) {
            DEPTH.remove();
        }
        if (traceIdOwner) {
            MDC.remove(MdcKeys.TRACE_ID);
        }
    }

    /**
     * 호출 깊이를 계단식 들여쓰기로 표현한다 — depth 1 은 들여쓰기 없음, 이후 한 단계마다 {@code |   }.
     * 방향(진입 →/종료 ←/예외 ✗)은 기존 글리프가 그대로 나타낸다.
     *
     * <pre>
     * → [web]     OrderController.pay
     * |   → [service] PaymentService.capture
     * |   |   ✗ [service] LedgerService.post failed after 3ms — ...
     * |   ← [service] PaymentService.capture 12ms
     * ← [web]     OrderController.pay 14ms
     * </pre>
     */
    private static String prefix(int depth) {
        return depth <= 1 ? "" : "|   ".repeat(depth - 1);
    }

    private void record(String layer, String type, String method, String outcome, long elapsedNanos) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            Timer.builder(TIMER_NAME)
                    .tag("layer", layer)
                    .tag("class", type)
                    .tag("method", method)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // 메트릭 기록 실패가 비즈니스 흐름을 막아선 안 된다.
            log.debug("Failed to record method timer for {}.{}", type, method, e);
        }
    }

    private static String layerOf(String declaringClassName) {
        if (declaringClassName.contains(".adapter.in.web")) {
            return "web";
        }
        if (declaringClassName.contains(".adapter.in.kafka")) {
            return "kafka";
        }
        if (declaringClassName.contains(".adapter.in.batch")) {
            return "batch";
        }
        if (declaringClassName.contains(".application.service")) {
            return "service";
        }
        return "other";
    }

    private String argsSuffix(ProceedingJoinPoint joinPoint) {
        if (!properties.isLogArgs()) {
            return "";
        }
        Object[] args = joinPoint.getArgs();
        if (args.length == 0) {
            return "()";
        }
        String rendered = Arrays.stream(args)
                .map(this::renderArg)
                .collect(Collectors.joining(", ", "(", ")"));
        return rendered;
    }

    private String renderArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        String value;
        try {
            value = String.valueOf(arg);
        } catch (RuntimeException e) {
            return arg.getClass().getSimpleName() + "@?";
        }
        int max = properties.getMaxArgLength();
        if (value.length() > max) {
            return value.substring(0, max) + "…";
        }
        return value;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
