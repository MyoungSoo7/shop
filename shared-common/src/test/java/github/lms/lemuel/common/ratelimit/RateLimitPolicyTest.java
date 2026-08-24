package github.lms.lemuel.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPolicyTest {

    @Test
    @DisplayName("matches() 는 null 을 거부하고 prefix 기반으로 경로를 매칭한다")
    void matchesByPathPrefix() {
        RateLimitPolicy policy = new RateLimitPolicy(
                "checkout",
                "/api/orders",
                RateLimitPolicy.byIp(),
                10,
                Duration.ofSeconds(30)
        );

        assertThat(policy.matches(null)).isFalse();
        assertThat(policy.matches("/api/products")).isFalse();
        assertThat(policy.matches("/api/orders/checkout")).isTrue();
    }

    @Test
    @DisplayName("byActorOrIp() 는 인증 사용자는 actor 키, 미인증은 IP 키를 사용한다")
    void byActorOrIpChoosesStableKey() {
        Function<RateLimitKeySource, String> extractor = RateLimitPolicy.byActorOrIp();

        assertThat(extractor.apply(new RateLimitKeySource("10.0.0.1", "user@example.com")))
                .isEqualTo("actor:user@example.com");
        assertThat(extractor.apply(new RateLimitKeySource("10.0.0.1", "")))
                .isEqualTo("ip:10.0.0.1");
        assertThat(extractor.apply(new RateLimitKeySource("10.0.0.2", null)))
                .isEqualTo("ip:10.0.0.2");
    }

    @Test
    @DisplayName("RateLimitConfig 는 기본 정책과 필터 등록 정보를 구성한다")
    void configCreatesFilterRegistration() {
        RateLimitConfig config = new RateLimitConfig();
        RateLimitRegistry registry = new RateLimitRegistry();
        List<RateLimitPolicy> policies = config.rateLimitPolicies();
        RateLimitFilter filter = config.rateLimitFilter(registry, policies);
        FilterRegistrationBean<RateLimitFilter> registration = config.rateLimitFilterRegistration(filter);

        assertThat(policies).isNotEmpty();
        assertThat(registration.getFilter()).isSameAs(filter);
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }
}
