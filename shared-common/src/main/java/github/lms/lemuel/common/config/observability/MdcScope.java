package github.lms.lemuel.common.config.observability;

import org.slf4j.MDC;

/**
 * 지정된 MDC 키를 범위 내에서만 부착하는 try-with-resources 유틸.
 *
 * <pre>{@code
 *     try (var ignore = MdcScope.of(MdcKeys.PAYMENT_ID, String.valueOf(paymentId))) {
 *         // 이 블록 안 로그에는 paymentId 가 부착된다
 *         doSomething();
 *     }
 * }</pre>
 *
 * <p>기존 값이 있다면 블록 종료 시 복원한다. 비동기·재귀 호출에서 안전.
 */
public final class MdcScope implements AutoCloseable {

    private final String key;
    private final String previous;

    private MdcScope(String key, String value) {
        this.key = key;
        this.previous = MDC.get(key);
        MDC.put(key, value);
    }

    public static MdcScope of(String key, String value) {
        return new MdcScope(key, value);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }
}
