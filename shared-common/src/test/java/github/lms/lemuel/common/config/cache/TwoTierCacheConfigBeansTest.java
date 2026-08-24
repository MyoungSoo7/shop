package github.lms.lemuel.common.config.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TwoTierCacheConfig 의 @Bean 팩토리 메서드를 Redis 연결 없이 목으로 직접 구동해 커버한다.
 * (실제 L2 라운드트립은 Testcontainers 기반 TwoTierCacheIntegrationTest 가 별도 검증)
 */
class TwoTierCacheConfigBeansTest {

    private TwoTierCacheConfig config;

    @BeforeEach
    void setUp() {
        config = new TwoTierCacheConfig();
        ReflectionTestUtils.setField(config, "l1TtlSeconds", 60L);
        ReflectionTestUtils.setField(config, "l2TtlSeconds", 600L);
        ReflectionTestUtils.setField(config, "l1MaxSize", 500L);
        ReflectionTestUtils.setField(config, "cbWindowSize", 4);
        ReflectionTestUtils.setField(config, "cbMinCalls", 2);
        ReflectionTestUtils.setField(config, "cbFailureRate", 50f);
        ReflectionTestUtils.setField(config, "cbWaitOpenSeconds", 10L);
    }

    @Test
    @DisplayName("cacheRedisTemplate: 직렬화기 구성된 RedisTemplate")
    void cacheRedisTemplate() {
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> template = config.cacheRedisTemplate(cf);
        assertThat(template.getKeySerializer()).isNotNull();
        assertThat(template.getValueSerializer()).isNotNull();
    }

    @Test
    @DisplayName("cacheInvalidationPublisher / l2RedisCircuitBreaker 생성")
    void publisherAndCircuitBreaker() {
        StringRedisTemplate stringTemplate = mock(StringRedisTemplate.class);
        assertThat(config.cacheInvalidationPublisher(stringTemplate)).isNotNull();

        CircuitBreaker cb = config.l2RedisCircuitBreaker();
        assertThat(cb.getName()).isEqualTo("l2-redis");
    }

    @Test
    @DisplayName("cacheManager + cacheInvalidationListener + container 배선")
    @SuppressWarnings("unchecked")
    void cacheManagerAndListenerAndContainer() {
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> redisTemplate = config.cacheRedisTemplate(cf);
        CacheInvalidationPublisher publisher = config.cacheInvalidationPublisher(mock(StringRedisTemplate.class));
        CircuitBreaker cb = config.l2RedisCircuitBreaker();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());

        CacheManager manager = config.cacheManager(redisTemplate, publisher, cb, provider);
        assertThat(manager).isInstanceOf(TwoTierCacheManager.class);
        assertThat(manager.getCacheNames()).containsAll(CacheNames.ALL);

        CacheInvalidationListener listener = config.cacheInvalidationListener(manager);
        assertThat(listener).isNotNull();

        RedisMessageListenerContainer container = config.cacheInvalidationContainer(cf, listener);
        assertThat(container).isNotNull();
    }
}
