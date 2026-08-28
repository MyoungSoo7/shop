package github.lms.lemuel.partner.config;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 파트너 백오피스 경로 보안.
 *
 * <p>체인이 하나뿐이고 <b>공개 경로가 없다.</b> 이 서비스가 내놓는 모든 응답은 특정 기업의
 * 매출이다 — 비로그인에 열어 줄 것이 하나도 없다.
 *
 * <p>인증만으로는 부족하다는 점이 이 서비스의 핵심이다. 로그인한 사람이라면 누구나 토큰을
 * 가지고 있고, 여기서 막지 않으면 A사 직원이 B사 매출을 볼 수 있다. 그 두 번째 방어는
 * 이 설정이 아니라 코드에 있다 — 컨트롤러는 조회 대상 조직을 요청에서 받지 않고 JWT 의
 * subject 로만 정한다({@code CurrentPartnerUser} → {@code ResolvePartnerScopeUseCase}).
 * 요청 파라미터로 조직·셀러를 받는 순간 이 서비스는 남의 매출 조회 API 가 된다.
 *
 * <p>{@code @Order(7)} 은 형제 모듈과 겹치지 않게 고른 값이다 — 1~4 는 operation-service,
 * 5~6 은 marketing-service 가 쓴다. 같은 순서 값이 둘이면 뜨는 순서에 따라 한쪽 체인이
 * 통째로 죽고, 그 사실은 요청이 엉뚱한 체인에 걸릴 때에야 드러난다.
 */
@Configuration
@EnableWebSecurity
@Import({JwtUtil.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
public class PartnerSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public PartnerSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(7)
    // CSRF 비활성 경고(java:S4502) 억제 — STATELESS 토큰 API 다. 브라우저가 자동으로 실어 보내는
    // 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 교차 사이트 요청이 권한을 획득할 수 없다.
    // 세션·쿠키 인증을 도입하면 이 억제는 무효이며 CSRF 보호를 되살려야 한다.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain partnerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/partner/**")
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 경로를 명시하고 나머지를 denyAll 로 닫는다. securityMatcher 가 이미
                        // 같은 범위를 잡고 있어 결과는 같지만, 그러면 인가 결정이 matcher 안에
                        // 숨는다 — 나중에 matcher 가 넓어지면 아무 신호 없이 범위가 늘어난다.
                        .requestMatchers("/api/partner/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
