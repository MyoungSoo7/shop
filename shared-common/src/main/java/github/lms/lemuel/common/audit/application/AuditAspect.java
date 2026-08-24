package github.lms.lemuel.common.audit.application;

import github.lms.lemuel.common.audit.domain.AuditAction;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Auditable} 이 붙은 애플리케이션 유스케이스의 성공/실패를 audit_logs 에 기록한다.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditLogger auditLogger;
    private final AuditDetailSerializer detailSerializer;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditLogger auditLogger, AuditDetailSerializer detailSerializer) {
        this.auditLogger = auditLogger;
        this.detailSerializer = detailSerializer;
    }

    @Around("@annotation(auditable)")
    public Object recordAudit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            if (auditable.recordOnSuccess()) {
                record(joinPoint, auditable, auditable.action(), result, null);
            }
            return result;
        } catch (Throwable error) {
            if (auditable.recordOnFailure()) {
                record(joinPoint, auditable, safeFailureAction(auditable), null, error);
            }
            throw error;
        }
    }

    private void record(ProceedingJoinPoint joinPoint,
                        Auditable auditable,
                        AuditAction action,
                        Object result,
                        Throwable error) {
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            StandardEvaluationContext context = evaluationContext(joinPoint, method, result, error);

            String resourceId = safeEvaluateString(auditable.resourceId(), context);
            Object detail = safeEvaluate(auditable.detail(), context);
            String detailJson = detailSerializer.toJson(enrichDetail(method, detail, error));

            auditLogger.record(action, auditable.resourceType(), resourceId, detailJson);
        } catch (Exception e) {
            log.error("Audit aspect failed. action={}, resourceType={}",
                    action, auditable.resourceType(), e);
        }
    }

    private StandardEvaluationContext evaluationContext(ProceedingJoinPoint joinPoint,
                                                       Method method,
                                                       Object result,
                                                       Throwable error) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        Object[] args = joinPoint.getArgs();
        context.setVariable("args", args);
        context.setVariable("result", result);
        context.setVariable("error", error);
        context.setVariable("method", method.getName());
        context.setVariable("target", joinPoint.getTarget());

        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }

        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        return context;
    }

    private Object evaluate(String expression, StandardEvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        return parser.parseExpression(expression).getValue(context);
    }

    private Object safeEvaluate(String expression, StandardEvaluationContext context) {
        try {
            return evaluate(expression, context);
        } catch (Exception e) {
            return Map.of("auditExpressionError", e.getClass().getSimpleName());
        }
    }

    private AuditAction failureAction(Auditable auditable) {
        if (auditable.failureAction().isBlank()) {
            return auditable.action();
        }
        return AuditAction.valueOf(auditable.failureAction());
    }

    private AuditAction safeFailureAction(Auditable auditable) {
        try {
            return failureAction(auditable);
        } catch (Exception e) {
            log.error("Invalid audit failure action. configured={}, fallback={}",
                    auditable.failureAction(), auditable.action(), e);
            return auditable.action();
        }
    }

    private String safeEvaluateString(String expression, StandardEvaluationContext context) {
        try {
            return stringify(evaluate(expression, context));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> enrichDetail(Method method, Object detail, Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (detail instanceof Map<?, ?> map) {
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
        } else if (detail != null) {
            payload.put("detail", detail);
        }
        payload.putIfAbsent("method", method.getDeclaringClass().getSimpleName() + "." + method.getName());
        payload.put("outcome", error == null ? "SUCCESS" : "FAILURE");
        if (error != null) {
            payload.put("errorType", error.getClass().getSimpleName());
            payload.put("errorMessage", error.getMessage());
        }
        return payload;
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
