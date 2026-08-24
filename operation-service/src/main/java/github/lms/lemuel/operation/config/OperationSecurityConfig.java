package github.lms.lemuel.operation.config;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * {@code /api/ops/**} 전용 보안 체인.
 *
 * <p>루트 스캔으로 shared-common 의 SecurityConfig(전역 체인, 순서 미지정 = 최후순)도 함께 뜨므로,
 * 이 체인을 {@code @Order(1)} + securityMatcher 로 앞에 세워 운영 콘솔 경로만 가로챈다.
 * 나머지 경로(actuator/swagger 등)는 기존 전역 체인이 그대로 처리한다.
 *
 * <ul>
 *   <li>webhook — permitAll. 게이팅은 {@link OpsWebhookAuthFilter}(Bearer INTERNAL_API_KEY) 가 담당.
 *       Alertmanager 는 compose 내부에서 직접 호출하며, 게이트웨이 경유 외부 유입도 같은 필터가 차단.</li>
 *   <li>그 외 /api/ops/** — 운영자 콘솔이므로 JWT ROLE_ADMIN 전용.</li>
 * </ul>
 */
@Configuration
public class OperationSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OpsWebhookAuthFilter opsWebhookAuthFilter;

    public OperationSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                                   OpsWebhookAuthFilter opsWebhookAuthFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.opsWebhookAuthFilter = opsWebhookAuthFilter;
    }

    @Bean
    @Order(1)
    // CSRF 비활성 경고(java:S4502) 억제 — 이 체인은 세션을 만들지 않는(STATELESS) 토큰 API 다.
    // 브라우저가 자동으로 실어 보내는 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 교차 사이트
    // 요청이 사용자 권한을 획득할 수 없다 — CSRF 토큰이 방어할 대상 자체가 없다.
    // 세션·쿠키 인증을 도입하는 순간 이 억제는 무효이며 CSRF 보호를 되살려야 한다.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain opsSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/ops/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/ops/webhook/**").permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(opsWebhookAuthFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
