package github.lms.lemuel.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import github.lms.lemuel.common.config.cache.CacheNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 로컬 전용 Caffeine 캐시 (기본).
 *
 * <p>{@code app.cache.two-tier.enabled=true} 이면 이 설정은 비활성화되고
 * {@link github.lms.lemuel.common.config.cache.TwoTierCacheConfig} 의 L1+L2 캐시 매니저가 대신 등록된다.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "app.cache.two-tier.enabled", havingValue = "false", matchIfMissing = true)
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
            CacheNames.ALL.toArray(new String[0])
        );
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(500)
        );
        return manager;
    }
}