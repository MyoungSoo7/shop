package github.lms.lemuel.common.observability.aop;

import github.lms.lemuel.common.config.observability.MdcKeys;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * {@code @Transactional} 메서드의 경계(BEGIN / COMMIT / ROLLBACK)를 추적하는 Aspect.
 *
 * <p>각 트랜잭션 메서드 진입 시:
 * <ul>
 *   <li>스레드 단위 트랜잭션 시퀀스로 {@code txId} 를 만들어 MDC 에 부착 → 같은 트랜잭션 안의 모든 로그가 연결된다.</li>
 *   <li>중첩 깊이(depth)를 추적해 PROPAGATION 으로 인한 중첩/합류를 가시화.</li>
 *   <li>{@code @Transactional} 의 propagation·readOnly 속성을 함께 로깅.</li>
 *   <li>정상 종료 시 COMMIT(논리), 예외 시 ROLLBACK(논리) 으로 소요시간과 함께 기록.</li>
 * </ul>
 *
 * <p>여기서 말하는 COMMIT/ROLLBACK 은 <em>논리적 경계</em> 다. 실제 물리 커밋은 Spring 트랜잭션
 * 인터셉터가 수행하며, 이 Aspect 는 그 바깥에서 메서드 호출 성공/실패를 기준으로 기록한다.
 * 실제 트랜잭션 활성 여부는 {@link TransactionSynchronizationManager} 로 보강한다.
 *
 * <p>{@link Order} 는 {@link MethodTraceAspect} 보다 안쪽(값이 큼), 실제 트랜잭션 인터셉터
 * (기본 {@link Ordered#LOWEST_PRECEDENCE}) 보다 바깥에 위치한다.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TransactionTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(TransactionTraceAspect.class);

    /** 스레드별 트랜잭션 일련번호 — txId 생성용. */
    private static final ThreadLocal<long[]> TX_SEQUENCE = ThreadLocal.withInitial(() -> new long[]{0});
    /** 스레드별 중첩 깊이. */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    @Around("github.lms.lemuel.common.observability.aop.LemuelPointcuts.transactional()")
    public Object traceTransaction(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!log.isDebugEnabled() && !log.isWarnEnabled()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String label = signature.getDeclaringType().getSimpleName() + "." + signature.getName();

        Transactional tx = findTransactional(method, signature.getDeclaringType());
        int depth = ++DEPTH.get()[0];

        String priorTxId = MDC.get(MdcKeys.TX_ID);
        boolean newLogicalTx = priorTxId == null || requiresNew(tx);
        String txId = newLogicalTx ? nextTxId() : priorTxId;

        if (newLogicalTx) {
            MDC.put(MdcKeys.TX_ID, txId);
        }

        long startNanos = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("⟦TX-BEGIN {} d{}⟧ {} [{}{}]",
                    txId, depth, label, propagationOf(tx), tx != null && tx.readOnly() ? ", readOnly" : "");
        }

        try {
            Object result = joinPoint.proceed();
            if (log.isDebugEnabled()) {
                log.debug("⟦TX-COMMIT {} d{}⟧ {} {}ms (active={})",
                        txId, depth, label, elapsedMs(startNanos),
                        TransactionSynchronizationManager.isActualTransactionActive());
            }
            return result;
        } catch (Throwable error) {
            log.warn("⟦TX-ROLLBACK {} d{}⟧ {} {}ms — {}: {}",
                    txId, depth, label, elapsedMs(startNanos),
                    error.getClass().getSimpleName(), error.getMessage());
            throw error;
        } finally {
            DEPTH.get()[0]--;
            if (DEPTH.get()[0] <= 0) {
                DEPTH.remove();
                TX_SEQUENCE.remove();
            }
            // 이 호출이 txId 를 새로 부착했다면 이전 값으로 복원.
            if (newLogicalTx) {
                if (priorTxId == null) {
                    MDC.remove(MdcKeys.TX_ID);
                } else {
                    MDC.put(MdcKeys.TX_ID, priorTxId);
                }
            }
        }
    }

    private static Transactional findTransactional(Method method, Class<?> declaringType) {
        Transactional onMethod = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(declaringType, Transactional.class);
    }

    private static boolean requiresNew(Transactional tx) {
        return tx != null && tx.propagation() == org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;
    }

    private static String propagationOf(Transactional tx) {
        return tx == null ? "REQUIRED" : tx.propagation().name();
    }

    private static String nextTxId() {
        long seq = ++TX_SEQUENCE.get()[0];
        return "tx-" + Long.toString(System.nanoTime() & 0xFFFFFF, 36) + "-" + seq;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
