package github.lms.lemuel.common.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link TwoTierCache} 들을 이름별로 보유하는 캐시 매니저.
 *
 * <p>{@link CacheNames#ALL} 의 이름만 정적으로 생성한다(미등록 이름은 {@code null} 반환).
 * 각 캐시는 자기 전용 L1(Caffeine)을 갖고, L2(Redis)·무효화 발행기는 공유한다.
 */
public class TwoTierCacheManager implements CacheManager {

    private final Map<String, TwoTierCache> caches = new LinkedHashMap<>();

    /** 메트릭 없이 생성(직접 생성 테스트 등). hit/miss 카운터는 비활성, DEBUG 로그만 동작한다. */
    public TwoTierCacheManager(Collection<String> cacheNames,
                               RedisTemplate<String, Object> redisTemplate,
                               CacheInvalidationPublisher publisher,
                               Duration l1Ttl,
                               Duration l2Ttl,
                               long l1MaxSize,
                               boolean allowNullValues) {
        this(cacheNames, redisTemplate, publisher, l1Ttl, l2Ttl, l1MaxSize, allowNullValues, null);
    }

    public TwoTierCacheManager(Collection<String> cacheNames,
                               RedisTemplate<String, Object> redisTemplate,
                               CacheInvalidationPublisher publisher,
                               Duration l1Ttl,
                               Duration l2Ttl,
                               long l1MaxSize,
                               boolean allowNullValues,
                               @Nullable MeterRegistry meterRegistry) {
        this(cacheNames, redisTemplate, publisher, l1Ttl, l2Ttl, l1MaxSize, allowNullValues, meterRegistry, null);
    }

    public TwoTierCacheManager(Collection<String> cacheNames,
                               RedisTemplate<String, Object> redisTemplate,
                               CacheInvalidationPublisher publisher,
                               Duration l1Ttl,
                               Duration l2Ttl,
                               long l1MaxSize,
                               boolean allowNullValues,
                               @Nullable MeterRegistry meterRegistry,
                               @Nullable CircuitBreaker l2CircuitBreaker) {
        for (String name : cacheNames) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = Caffeine.newBuilder()
                    .expireAfterWrite(l1Ttl)
                    .maximumSize(l1MaxSize)
                    .build();
            caches.put(name, new TwoTierCache(name, l1, redisTemplate, l2Ttl, publisher, allowNullValues, meterRegistry, l2CircuitBreaker));
        }
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        return caches.get(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(caches.keySet());
    }

    /** Pub/Sub 리스너가 로컬 L1 무효화를 위해 이름으로 캐시를 찾는다. */
    @Nullable
    TwoTierCache twoTierCache(String name) {
        return caches.get(name);
    }
}
