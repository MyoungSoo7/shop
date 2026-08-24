package github.lms.lemuel.common.config.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 2-tier 캐시: L1(Caffeine, 인스턴스 로컬) + L2(Redis, 공유).
 *
 * <p>조회 경로: L1 hit → 반환 / L1 miss → L2 조회 후 적중 시 L1 채우고 반환 / 둘 다 miss → null(로더 호출).
 * 쓰기 경로: L1·L2 동시 기록 + 다른 인스턴스 L1 무효화 Pub/Sub.
 *
 * <p><b>graceful degrade</b>: L2(Redis) 호출은 모두 try/catch 로 감싸 Redis 장애 시 L1/DB 로 폴백한다.
 * Redis 가 죽어도 애플리케이션은 (로컬 캐시/DB 로) 계속 동작한다.
 *
 * <p><b>null 값</b>: NullValue 는 L1 에만 두고 L2(Redis)에는 쓰지 않는다(직렬화 회피). 실제 캐시 대상
 * 메서드는 non-null 을 반환하므로 일반 경로엔 영향 없다.
 */
public class TwoTierCache extends AbstractValueAdaptingCache {

    private static final Logger log = LoggerFactory.getLogger(TwoTierCache.class);

    /** 조회 결과 카운터 — Prometheus: {@code lemuel_cache_requests_total{cache,result}}. */
    private static final String METRIC_REQUESTS = "lemuel.cache.requests";
    private static final String RESULT_L1_HIT = "l1_hit";
    private static final String RESULT_L2_HIT = "l2_hit";
    private static final String RESULT_MISS = "miss";

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1;
    private final RedisTemplate<String, Object> l2;
    private final Duration l2Ttl;
    private final CacheInvalidationPublisher publisher;
    /** 캐시 hit/miss 메트릭 발행기. 직접 생성 테스트 등 레지스트리가 없을 땐 {@code null} → 메트릭 생략, 로그만. */
    @Nullable
    private final MeterRegistry meterRegistry;
    /** L2(Redis) 호출 보호용 CircuitBreaker. {@code null} 이면 보호 없이 직접 호출(테스트 등). */
    @Nullable
    private final CircuitBreaker l2CircuitBreaker;

    public TwoTierCache(String name,
                        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1,
                        RedisTemplate<String, Object> l2,
                        Duration l2Ttl,
                        CacheInvalidationPublisher publisher,
                        boolean allowNullValues,
                        @Nullable MeterRegistry meterRegistry,
                        @Nullable CircuitBreaker l2CircuitBreaker) {
        super(allowNullValues);
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
        this.l2Ttl = l2Ttl;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
        this.l2CircuitBreaker = l2CircuitBreaker;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1;
    }

    /**
     * L1 → L2 순으로 조회. L1 적중 시 바로 반환. L1 미스 시 L2 조회 후 적중하면 L1 채우고 반환.
     * @param key the key whose associated value is to be returned
     * @return
     */
    @Override
    @Nullable
    protected Object lookup(Object key) {
        String k = str(key);
        Object v = l1.getIfPresent(k);
        if (v != null) {
            record(RESULT_L1_HIT, k);
            return v;
        }
        Object fromL2 = l2Get(k);
        if (fromL2 != null) {
            l1.put(k, fromL2);   // L2 적중분을 L1 으로 승격(near-cache)
            record(RESULT_L2_HIT, k);
            return fromL2;
        }
        record(RESULT_MISS, k);
        return null;
    }

    /**
     * 조회 결과(L1 hit / L2 hit / miss)를 메트릭 카운터와 DEBUG 로그로 기록한다.
     *
     * <p>Micrometer 카운터는 등록 후 내부 맵에 캐시되므로 매 조회마다 호출해도 비용이 낮다.
     * 로그는 {@code logging.level.github.lms.lemuel.common.config.cache=DEBUG} 일 때만 찍힌다.
     */
    private void record(String result, String k) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_REQUESTS, "cache", name, "result", result).increment();
        }
        if (log.isDebugEnabled()) {
            log.debug("cache lookup [{}] cache={} key={}", result, name, k);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object existing = lookup(key);
        if (existing != null) {
            return (T) fromStoreValue(existing);
        }
        // 로드-스루: 캐시 미스 시 로더 실행 후 양 계층에 기록.
        T value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
        put(key, value);
        return value;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        String k = str(key);
        Object storeValue = toStoreValue(value);
        l1.put(k, storeValue);
        if (!(storeValue instanceof NullValue)) {
            l2Put(k, storeValue);
        }
        publisher.publishEvict(name, k);   // 타 인스턴스의 stale L1 무효화 → 다음 조회 시 L2 에서 최신값
    }

    @Override
    public void evict(Object key) {
        String k = str(key);
        l1.invalidate(k);
        l2Delete(k);
        publisher.publishEvict(name, k);
    }

    @Override
    public void clear() {
        l1.invalidateAll();
        l2Clear();
        publisher.publishClear(name);
    }

    // --- Pub/Sub 수신 시 로컬 L1 만 조작 (L2 미터치, 재발행 없음) ---

    void evictLocal(Object key) {
        l1.invalidate(key);
    }

    void clearLocal() {
        l1.invalidateAll();
    }

    // --- L2(Redis) 접근 — 전부 graceful degrade + CircuitBreaker. key 는 이미 문자열화된 값(k). ---

    @Nullable
    private Object l2Get(String k) {
        return l2Execute("get", k, () -> l2.opsForValue().get(redisKey(k)));
    }

    private void l2Put(String k, Object storeValue) {
        l2Execute("put", k, () -> {
            l2.opsForValue().set(redisKey(k), storeValue, l2Ttl);
            return null;
        });
    }

    private void l2Delete(String k) {
        l2Execute("delete", k, () -> {
            l2.delete(redisKey(k));
            return null;
        });
    }

    private void l2Clear() {
        l2Execute("clear", "*", () -> {
            Set<String> keys = l2.keys(name + "::*");
            if (keys != null && !keys.isEmpty()) {
                l2.delete(keys);
            }
            return null;
        });
    }

    /**
     * L2(Redis) 호출 공통 실행기 — graceful degrade.
     *
     * <p>CircuitBreaker 가 있으면 호출을 감싼다. 연속 실패로 회로가 OPEN 되면 이후 호출은 Redis 까지
     * 가지 않고 즉시 {@link CallNotPermittedException} 으로 차단되어 <b>타임아웃 대기(수백 ms)조차
     * 사라진다</b>. 회로 차단은 DEBUG, 실제 호출 실패는 WARN 으로 남기고 어느 쪽이든 null 폴백한다.
     */
    @Nullable
    private Object l2Execute(String op, String k, Supplier<Object> action) {
        try {
            return l2CircuitBreaker != null ? l2CircuitBreaker.executeSupplier(action) : action.get();
        } catch (CallNotPermittedException e) {
            if (log.isDebugEnabled()) {
                log.debug("L2(Redis) circuit OPEN, skip {} cache={} key={}", op, name, k);
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("L2(Redis) {} failed, falling back. cache={}, key={}, error={}", op, name, k, e.getMessage());
            return null;
        }
    }

    private String redisKey(String k) {
        return name + "::" + k;
    }

    /** Spring 이 넘기는 키 객체(Long/String 등)를 분산 캐시·Pub/Sub 에서 일관되도록 문자열로 정규화. */
    private static String str(Object key) {
        return String.valueOf(key);
    }
}
