package github.lms.lemuel.common.observability.aop;

import ch.qos.logback.classic.Level;
import github.lms.lemuel.common.config.observability.MdcKeys;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TransactionTraceAspect — 논리적 트랜잭션 경계(BEGIN/COMMIT/ROLLBACK) 추적을 목 조인포인트로 구동.
 * warn 로깅이 기본 활성이라 트레이싱 본문이 실행된다.
 */
class TransactionTraceAspectTest {

    private final TransactionTraceAspect aspect = new TransactionTraceAspect();

    private static Level originalLevel;

    @BeforeAll
    static void enableDebug() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TransactionTraceAspect.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG); // BEGIN/COMMIT debug 로그 경로 활성화
    }

    @AfterAll
    static void restoreLevel() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TransactionTraceAspect.class)).setLevel(originalLevel);
    }

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    static class TxSample {
        @Transactional
        public void required() { }

        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public void requiresNew() { }

        public void plain() { }
    }

    private static ProceedingJoinPoint jp(String methodName, Object proceedResult, Throwable toThrow) throws Throwable {
        Method method = TxSample.class.getMethod(methodName);
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(method);
        when(sig.getName()).thenReturn(methodName);
        org.mockito.Mockito.<Class<?>>when(sig.getDeclaringType()).thenReturn(TxSample.class);
        when(jp.getSignature()).thenReturn(sig);
        if (toThrow != null) {
            when(jp.proceed()).thenThrow(toThrow);
        } else {
            when(jp.proceed()).thenReturn(proceedResult);
        }
        return jp;
    }

    @Test
    @DisplayName("@Transactional 정상 종료 → COMMIT 경계, MDC txId 복원(제거)")
    void tracesCommit() throws Throwable {
        assertThat(MDC.get(MdcKeys.TX_ID)).isNull();
        Object result = aspect.traceTransaction(jp("required", "ok", null));
        assertThat(result).isEqualTo("ok");
        assertThat(MDC.get(MdcKeys.TX_ID)).isNull(); // priorTxId null → 제거됨
    }

    @Test
    @DisplayName("예외 → ROLLBACK 경계 후 재던짐")
    void tracesRollback() throws Throwable {
        RuntimeException boom = new IllegalStateException("fail");
        assertThatThrownBy(() -> aspect.traceTransaction(jp("required", null, boom))).isSameAs(boom);
        assertThat(MDC.get(MdcKeys.TX_ID)).isNull();
    }

    @Test
    @DisplayName("REQUIRES_NEW 는 기존 txId 가 있어도 새 논리 트랜잭션을 열고 종료 시 복원")
    void requiresNewNestsAndRestores() throws Throwable {
        MDC.put(MdcKeys.TX_ID, "outer-tx");
        aspect.traceTransaction(jp("requiresNew", "ok", null));
        assertThat(MDC.get(MdcKeys.TX_ID)).isEqualTo("outer-tx"); // 복원됨
    }

    @Test
    @DisplayName("@Transactional 없는 메서드도 안전하게 추적(REQUIRED 기본 표기)")
    void tracesNonTransactionalMethod() throws Throwable {
        MDC.put(MdcKeys.TX_ID, "outer");
        Object result = aspect.traceTransaction(jp("plain", "v", null));
        assertThat(result).isEqualTo("v");
        // priorTxId 있으므로 newLogicalTx=false, txId 유지
        assertThat(MDC.get(MdcKeys.TX_ID)).isEqualTo("outer");
    }
}
