package github.lms.lemuel.cart.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 장바구니 영속성 어댑터 (Redis).
 *
 * <p>장바구니 집합체 전체를 {@code cart:{userId}} 키에 JSON 한 덩어리로 저장한다. 사용자당 1 개의
 * 장바구니라는 도메인 규칙과 userId 단위 포트({@link LoadCartPort#loadByUserId})에 자연스럽게 들어맞는다.
 *
 * <p>저장할 때마다 30일 TTL 을 갱신한다 — 도메인의 "lastActiveAt 30일 TTL 정리" 정책을 Redis 만료로
 * 대체하므로 별도 cron 정리 배치가 필요 없다.
 *
 * <p>{@code cart.store=redis} 일 때만 활성화된다 (기본값 jpa → {@code CartPersistenceAdapter}).
 */
@Component
@ConditionalOnProperty(name = "cart.store", havingValue = "redis")
public class RedisCartAdapter implements LoadCartPort, SaveCartPort {

    private static final String KEY_PREFIX = "cart:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCartAdapter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Cart> loadByUserId(Long userId) {
        String json = redis.opsForValue().get(key(userId));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(json).toDomain());
    }

    @Override
    public Cart save(Cart cart) {
        redis.opsForValue().set(key(cart.getUserId()), serialize(RedisCart.from(cart)), TTL);
        return RedisCart.from(cart).toDomain();
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private String serialize(RedisCart cart) {
        try {
            return objectMapper.writeValueAsString(cart);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 발생할 수 없는 인프라 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("장바구니 직렬화 실패: userId=" + cart.userId(), e);
        }
    }

    private RedisCart deserialize(String json) {
        try {
            return objectMapper.readValue(json, RedisCart.class);
        } catch (JsonProcessingException e) {
            // 역직렬화 실패는 저장 포맷 손상 시의 인프라 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("장바구니 역직렬화 실패", e);
        }
    }
}
