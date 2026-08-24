package github.lms.lemuel.common.ratelimit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Rate Limit 정책·필터 빈 등록.
 *
 * <p>필터를 {@link FilterRegistrationBean} 으로 등록해 WebMvcTest 슬라이스에서의
 * 자동 등록을 피한다 (@Component 로 등록하면 슬라이스에 Registry 의존성이 없어 context 실패).
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public List<RateLimitPolicy> rateLimitPolicies() {
        return RateLimitFilter.defaultPolicies();
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitRegistry registry, List<RateLimitPolicy> policies) {
        return new RateLimitFilter(registry, policies);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
