package github.lms.lemuel.common.config.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig.securityFilterChain(HttpSecurity) 는 실제 HttpSecurity 빈이 필요하므로
 * 경량 서블릿 웹 컨텍스트에서 필터 체인이 빌드되는지 검증한다(60여 라인의 authorizeHttpRequests 룰 커버).
 * HttpSecurity 빈은 SecurityConfig 의 @EnableWebSecurity 가 등록한다(Boot 오토컨피그 불필요).
 */
class SecurityConfigContextTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(TestFilters.class, SecurityConfig.class)
            .withPropertyValues("cors.origins=http://localhost:3000");

    @Test
    @DisplayName("SecurityFilterChain 이 정상 빌드되고 PasswordEncoder/CORS 빈이 등록된다")
    void securityFilterChainBuilds() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
            assertThat(ctx).hasBean("passwordEncoder");
            assertThat(ctx).hasBean("corsConfigurationSource");
        });
    }

    static class TestFilters {
        @org.springframework.context.annotation.Bean
        JwtUtil jwtUtil() {
            JwtProperties props = new JwtProperties();
            props.setIssuer("t");
            props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
            props.setTtlSeconds(3600);
            return new JwtUtil(props);
        }

        @org.springframework.context.annotation.Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil) {
            return new JwtAuthenticationFilter(jwtUtil);
        }

        @org.springframework.context.annotation.Bean
        InternalApiKeyFilter internalApiKeyFilter() {
            return new InternalApiKeyFilter("k");
        }
    }
}
