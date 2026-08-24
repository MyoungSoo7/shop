package github.lms.lemuel.order.adapter.out.lock;

import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LockAcquisitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis 기반 분산 배타 락 — 멀티 인스턴스 환경에서 동일 키 작업을 직렬화한다.
 *
 * <p>획득: {@code SET lock:{key} {token} NX PX {leaseMs}} — 키가 없을 때만 세팅(원자적). 실패 시
 * {@code waitTime} 까지 짧은 백오프로 재시도.<br>
 * 해제: Lua CAS — 토큰이 내 것일 때만 DEL. 내 리스가 만료된 뒤 다른 보유자의 락을 실수로 지우지 않는다.<br>
 * 데드락 방지: leaseTime PX 로 보유 인스턴스가 죽어도 자동 만료.
 *
 * <p>{@code app.order.idempotency.distributed-lock=true} 일 때만 활성(기본은 InMemory 폴백).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.order.idempotency.distributed-lock", havingValue = "true")
public class RedisDistributedLockAdapter implements DistributedLockPort {

    private static final String LOCK_PREFIX = "lock:";
    /** 내가 세팅한 토큰일 때만 삭제(compare-and-delete) — 리스 만료 후 타 보유자 락 오삭제 방지. */
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final long BACKOFF_MILLIS = 50;

    private final StringRedisTemplate redis;

    public RedisDistributedLockAdapter(StringRedisTemplate redis) {
        this.redis = redis;
        log.info("DistributedLockPort = Redis(SETNX+Lua) — 멀티 인스턴스 분산 락 활성.");
    }

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        String lockKey = LOCK_PREFIX + key;
        String token = UUID.randomUUID().toString();
        long deadlineNanos = System.nanoTime() + waitTime.toNanos();

        boolean acquired = acquire(lockKey, token, leaseTime, deadlineNanos);
        if (!acquired) {
            throw new LockAcquisitionException("락 획득 시간 초과(redis): key=" + key);
        }
        try {
            return action.get();
        } finally {
            release(lockKey, token);
        }
    }

    private boolean acquire(String lockKey, String token, Duration leaseTime, long deadlineNanos) {
        while (true) {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, leaseTime);
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(BACKOFF_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockAcquisitionException("락 대기 중 인터럽트: " + lockKey, e);
            }
        }
    }

    private void release(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            // 해제 실패해도 leaseTime PX 로 자동 만료되므로 데드락은 없다 — 경고만 남긴다.
            log.warn("분산 락 해제 실패(자동 만료 대기): key={}, err={}", lockKey, e.getMessage());
        }
    }
}
