package github.lms.lemuel.common.config.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

/**
 * 타 인스턴스가 발행한 캐시 무효화 메시지를 받아 <b>자기 L1 만</b> 무효화한다.
 *
 * <p>L2(Redis)는 발행 인스턴스가 이미 갱신했으므로 건드리지 않고, 재발행도 하지 않는다(루프 방지).
 * 자기 자신이 보낸 메시지(originId 일치)는 무시한다.
 */
public class CacheInvalidationListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);

    private final TwoTierCacheManager cacheManager;
    private final String selfOriginId;

    public CacheInvalidationListener(TwoTierCacheManager cacheManager, String selfOriginId) {
        this.cacheManager = cacheManager;
        this.selfOriginId = selfOriginId;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        // 형식: originId|cacheName|key
        String[] parts = body.split("\\" + CacheInvalidationPublisher.DELIMITER, 3);
        if (parts.length != 3) {
            log.warn("Malformed cache invalidation message: {}", body);
            return;
        }
        String originId = parts[0];
        String cacheName = parts[1];
        String key = parts[2];

        if (selfOriginId.equals(originId)) {
            return;   // 자기 발행 — 이미 로컬 반영됨
        }

        TwoTierCache cache = cacheManager.twoTierCache(cacheName);
        if (cache == null) {
            return;
        }
        if (CacheInvalidationPublisher.CLEAR_TOKEN.equals(key)) {
            cache.clearLocal();
        } else {
            cache.evictLocal(key);
        }
    }
}
