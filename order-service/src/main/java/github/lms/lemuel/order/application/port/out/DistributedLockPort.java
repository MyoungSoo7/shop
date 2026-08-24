package github.lms.lemuel.order.application.port.out;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 분산 배타 락(뮤텍스) 아웃바운드 포트.
 *
 * <p>{@code key} 에 대한 상호배제 구간을 제공한다. 멀티 인스턴스 환경에서 동일 키의 동시 작업을
 * 직렬화하는 데 쓰인다(예: 동일 Idempotency-Key 의 중복 주문 제출 차단). 구현은 어댑터가 담당하며
 * (Redis SETNX+TTL / 단일 JVM 폴백), 애플리케이션·도메인은 Redis 등 구체 기술에 의존하지 않는다.
 *
 * <p>락은 <b>가용성·중복작업 방지</b>를 위한 것이지 유일한 정합성 수단이 아니다 — 락이 비활성/만료돼도
 * 호출부는 DB UNIQUE 제약 같은 영속 백스톱으로 최종 정합성을 보장해야 한다.
 */
public interface DistributedLockPort {

    /**
     * {@code key} 락을 획득해 {@code action} 을 실행하고, 끝나면 해제한다.
     *
     * @param key       락 키 (호출부에서 네임스페이스를 부여, 예: {@code "order:create:" + idempotencyKey})
     * @param waitTime  락 획득 최대 대기 시간 (초과 시 {@link LockAcquisitionException})
     * @param leaseTime 락 자동 만료 시간 (보유 중 인스턴스가 죽어도 이 시간 후 해제 → 데드락 방지)
     * @param action    임계 구역에서 실행할 작업
     * @return action 결과
     * @throws LockAcquisitionException 대기 시간 내 락을 얻지 못한 경우
     */
    <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action);
}
