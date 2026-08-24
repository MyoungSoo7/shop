package github.lms.lemuel.common.config.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 캐시 무효화 메시지를 Redis Pub/Sub 로 전파한다.
 *
 * <p>L1(Caffeine)은 인스턴스 로컬이라, 한 인스턴스가 값을 갱신/삭제하면 다른 인스턴스의 L1 은
 * 여전히 stale 하다. put/evict/clear 시 이 채널로 메시지를 쏘면 {@link CacheInvalidationListener}
 * 가 각 인스턴스에서 자기 L1 만 무효화해(L2 는 건드리지 않음) 다음 조회 때 L2 에서 최신값을 읽게 한다.
 *
 * <p>메시지 형식: {@code originId|cacheName|key}. {@code key} 가 {@link #CLEAR_TOKEN} 이면
 * 해당 캐시 전체 clear. originId 로 자기 자신이 보낸 메시지는 무시(루프 방지)한다.
 * 구분자 {@code |} 와 CLEAR_TOKEN 은 실제 캐시명/키/originId 에 등장하지 않는 값으로 고른다.
 */
public class CacheInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    static final String CHANNEL = "lemuel:cache:invalidation";
    static final String CLEAR_TOKEN = "::__CACHE_CLEAR__::";
    static final String DELIMITER = "|";

    private final StringRedisTemplate stringRedisTemplate;
    private final String originId;

    public CacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate, String originId) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.originId = originId;
    }

    public String originId() {
        return originId;
    }

    public void publishEvict(String cacheName, Object key) {
        send(cacheName, String.valueOf(key));
    }

    public void publishClear(String cacheName) {
        send(cacheName, CLEAR_TOKEN);
    }

    private void send(String cacheName, String key) {
        try {
            stringRedisTemplate.convertAndSend(CHANNEL, originId + DELIMITER + cacheName + DELIMITER + key);
        } catch (RuntimeException e) {
            // 무효화 전파 실패가 쓰기 흐름을 막지 않게 한다 — 로컬 L1/L2 는 이미 갱신됨, 타 노드는 TTL 로 수렴.
            log.warn("Cache invalidation publish failed. cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }
}
