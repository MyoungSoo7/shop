package github.lms.lemuel.common.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.port.out.SaveAuditLogPort;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.audit.domain.AuditLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * audit.application 단위 검증 — AuditAspect(SpEL 평가·성공/실패 분기),
 * AuditLogger(actor 결합·실패 격리), AuditDetailSerializer, AuditContext ThreadLocal.
 */
class AuditApplicationTest {

    @AfterEach
    void clear() {
        AuditContext.clear();
    }

    // 샘플 대상 — 실제 Method 로 파라미터명(id) 발견이 되게 한다.
    static class SampleService {
        public String doIt(String id) {
            return "ok-" + id;
        }
    }

    private static Method sampleMethod() throws NoSuchMethodException {
        return SampleService.class.getMethod("doIt", String.class);
    }

    private static ProceedingJoinPoint joinPoint(Method method, Object[] args, Object target) {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(method);
        when(jp.getSignature()).thenReturn(sig);
        when(jp.getArgs()).thenReturn(args);
        when(jp.getTarget()).thenReturn(target);
        return jp;
    }

    private static Auditable auditable(String resourceId, String detail, String failureAction,
                                       boolean onSuccess, boolean onFailure) {
        Auditable a = mock(Auditable.class);
        when(a.action()).thenReturn(AuditAction.SETTLEMENT_CONFIRMED);
        when(a.resourceType()).thenReturn("Settlement");
        when(a.resourceId()).thenReturn(resourceId);
        when(a.detail()).thenReturn(detail);
        when(a.failureAction()).thenReturn(failureAction);
        when(a.recordOnSuccess()).thenReturn(onSuccess);
        when(a.recordOnFailure()).thenReturn(onFailure);
        return a;
    }

    // ─── AuditAspect ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("성공 시 SpEL 로 resourceId/detail 평가 후 감사 기록")
    void recordsOnSuccess() throws Throwable {
        AuditLogger logger = mock(AuditLogger.class);
        AuditDetailSerializer serializer = new AuditDetailSerializer(new ObjectMapper());
        AuditAspect aspect = new AuditAspect(logger, serializer);

        Method m = sampleMethod();
        ProceedingJoinPoint jp = joinPoint(m, new Object[]{"100"}, new SampleService());
        when(jp.proceed()).thenReturn("result-value");

        Object result = aspect.recordAudit(jp, auditable("#p0", "{'k':#a0}", "", true, true));

        assertThat(result).isEqualTo("result-value");
        ArgumentCaptor<String> detailJson = ArgumentCaptor.forClass(String.class);
        verify(logger).record(eq(AuditAction.SETTLEMENT_CONFIRMED), eq("Settlement"), eq("100"), detailJson.capture());
        assertThat(detailJson.getValue()).contains("\"outcome\":\"SUCCESS\"").contains("\"method\":\"SampleService.doIt\"");
    }

    @Test
    @DisplayName("recordOnSuccess=false 면 성공 시 기록하지 않는다")
    void skipsRecordWhenSuccessDisabled() throws Throwable {
        AuditLogger logger = mock(AuditLogger.class);
        AuditAspect aspect = new AuditAspect(logger, new AuditDetailSerializer(new ObjectMapper()));
        ProceedingJoinPoint jp = joinPoint(sampleMethod(), new Object[]{"1"}, new SampleService());
        when(jp.proceed()).thenReturn("x");

        aspect.recordAudit(jp, auditable("#p0", "", "", false, true));

        verify(logger, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("예외 시 FAILURE outcome 으로 기록하고 예외를 재던진다")
    void recordsOnFailureAndRethrows() throws Throwable {
        AuditLogger logger = mock(AuditLogger.class);
        AuditAspect aspect = new AuditAspect(logger, new AuditDetailSerializer(new ObjectMapper()));
        ProceedingJoinPoint jp = joinPoint(sampleMethod(), new Object[]{"55"}, new SampleService());
        RuntimeException boom = new IllegalStateException("깨짐");
        when(jp.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect.recordAudit(jp, auditable("#p0", "", "", true, true)))
                .isSameAs(boom);

        ArgumentCaptor<String> detailJson = ArgumentCaptor.forClass(String.class);
        verify(logger).record(eq(AuditAction.SETTLEMENT_CONFIRMED), eq("Settlement"), eq("55"), detailJson.capture());
        assertThat(detailJson.getValue()).contains("\"outcome\":\"FAILURE\"")
                .contains("IllegalStateException").contains("깨짐");
    }

    @Test
    @DisplayName("failureAction 에 유효한 enum 이름 지정 시 그 action 으로 실패 기록")
    void usesConfiguredFailureAction() throws Throwable {
        AuditLogger logger = mock(AuditLogger.class);
        AuditAspect aspect = new AuditAspect(logger, new AuditDetailSerializer(new ObjectMapper()));
        ProceedingJoinPoint jp = joinPoint(sampleMethod(), new Object[]{"9"}, new SampleService());
        when(jp.proceed()).thenThrow(new RuntimeException("x"));

        assertThatThrownBy(() -> aspect.recordAudit(jp, auditable("#p0", "", "REFUND_COMPLETED", true, true)));

        verify(logger).record(eq(AuditAction.REFUND_COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("잘못된 SpEL resourceId 는 null 로, 잘못된 failureAction 은 원 action 으로 폴백")
    void tolerantOfBadExpressions() throws Throwable {
        AuditLogger logger = mock(AuditLogger.class);
        AuditAspect aspect = new AuditAspect(logger, new AuditDetailSerializer(new ObjectMapper()));
        ProceedingJoinPoint jp = joinPoint(sampleMethod(), new Object[]{"9"}, new SampleService());
        when(jp.proceed()).thenThrow(new RuntimeException("x"));

        // failureAction 이 enum 에 없는 이름 → safeFailureAction 이 원 action 으로 폴백
        assertThatThrownBy(() -> aspect.recordAudit(jp, auditable("#nope.bad", "#also.bad", "NOT_A_REAL_ACTION", true, true)));

        // resourceId 는 평가 실패 → null, action 은 원본 SETTLEMENT_CONFIRMED
        verify(logger).record(eq(AuditAction.SETTLEMENT_CONFIRMED), eq("Settlement"),
                eq(null), any());
    }

    // ─── AuditLogger ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("AuditContext 의 actor 를 결합해 AuditLog 저장")
    void loggerCombinesActor() {
        SaveAuditLogPort port = mock(SaveAuditLogPort.class);
        AuditLogger logger = new AuditLogger(port);
        AuditContext.set(new AuditContext.AuditActor(7L, "a@b.com", "1.2.3.4", "ua"));

        logger.record(AuditAction.SETTLEMENT_CONFIRMED, "Settlement", "100", "{}");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(port).save(captor.capture());
        assertThat(captor.getValue().getActorEmail()).isEqualTo("a@b.com");
        assertThat(captor.getValue().getResourceId()).isEqualTo("100");
    }

    @Test
    @DisplayName("저장 실패는 비즈니스 트랜잭션을 깨지 않는다(예외 삼킴)")
    void loggerSwallowsSaveFailure() {
        SaveAuditLogPort port = mock(SaveAuditLogPort.class);
        doThrow(new RuntimeException("db down")).when(port).save(any());
        AuditLogger logger = new AuditLogger(port);

        logger.record(AuditAction.REFUND_COMPLETED, "Refund", "1", null); // 예외 안 던짐
        verify(port).save(any());
    }

    // ─── AuditDetailSerializer ───────────────────────────────────────────────

    @Test
    @DisplayName("정상 직렬화 및 실패 시 폴백 JSON")
    void serializerSuccessAndFailure() throws JsonProcessingException {
        AuditDetailSerializer ok = new AuditDetailSerializer(new ObjectMapper());
        assertThat(ok.toJson(Map.of("a", 1))).isEqualTo("{\"a\":1}");

        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
        AuditDetailSerializer bad = new AuditDetailSerializer(failing);
        assertThat(bad.toJson(new Object())).contains("audit_detail_serialization_failed");
    }

    // ─── AuditContext ────────────────────────────────────────────────────────

    @Test
    @DisplayName("미설정 시 system actor(null 필드) 반환, clear 후에도 system")
    void contextDefaultsToSystem() {
        assertThat(AuditContext.get().actorEmail()).isNull();
        AuditContext.set(new AuditContext.AuditActor(1L, "x@y.com", null, null));
        assertThat(AuditContext.get().actorEmail()).isEqualTo("x@y.com");
        AuditContext.clear();
        assertThat(AuditContext.get().actorId()).isNull();
    }
}
