package github.lms.lemuel.order.adapter.out.lock;

import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LockAcquisitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 단일 JVM 분산 락 폴백 — Redis 가 비활성일 때 사용.
 *
 * <p>고정 크기 스트라이프(ReentrantLock 배열)로 key 를 해싱해 잠근다. 메모리 무한 증가 없이 동일 키의
 * 동시 실행을 직렬화한다(서로 다른 키가 같은 스트라이프를 공유하면 드물게 추가 직렬화가 생기나 무해).
 *
 * <p><b>한계</b>: 같은 JVM 안에서만 유효하다. 멀티 인스턴스 환경의 최종 중복 차단은 호출부의 DB UNIQUE
 * 백스톱이 보장한다. 멀티 인스턴스 직렬화가 필요하면 {@code app.order.idempotency.distributed-lock=true}
 * 로 Redis 어댑터를 켠다. leaseTime 은 단일 JVM 동기 보유라 의미가 없어 무시한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.order.idempotency.distributed-lock", havingValue = "false", matchIfMissing = true)
public class InMemoryDistributedLockAdapter implements DistributedLockPort {

    private static final int STRIPES = 256;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    public InMemoryDistributedLockAdapter() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
        log.info("DistributedLockPort = InMemory(striped) — 단일 JVM 폴백. 멀티 인스턴스 직렬화는 DB UNIQUE 백스톱에 의존.");
    }

    private ReentrantLock lockFor(String key) {
        int idx = (key.hashCode() & 0x7fffffff) % STRIPES;
        return stripes[idx];
    }

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        ReentrantLock lock = lockFor(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new LockAcquisitionException("락 획득 시간 초과(in-memory): key=" + key);
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("락 대기 중 인터럽트: key=" + key, e);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }
}
