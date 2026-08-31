package github.lms.lemuel.seller.config;

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
 * 셀러 백오피스 경로 보안.
 *
 * <p>체인이 하나뿐이고 <b>공개 경로가 없다.</b> 이 서비스가 내놓는 모든 응답은 특정 셀러의
 * 상품과 주문이다 — 비로그인에 열어 줄 것이 하나도 없다.
 *
 * <p>여기에는 <b>인가 규칙이 두 겹</b>이다.
 * <ol>
 *   <li>{@code /api/seller/admin/**} → {@code ROLE_ADMIN}. 심사(승인·반려)는 운영자만 한다.
 *       셀러 스코프로는 판별할 수 없는 유일한 경로라, 이 한 줄이 인가 근거의 전부다.
 *       <b>순서가 중요하다</b> — 아래의 포괄 {@code authenticated()} 보다 먼저 와야 한다.
 *       뒤에 두면 먼저 걸린 규칙이 이기고, 그러면 아무 셀러나 남의 신청서를 승인한다.</li>
 *   <li>나머지 {@code /api/seller/**} → 인증. 대상 좁히기는 시큐리티가 아니라 코드가 한다
 *       ({@code CurrentSellerUser} → {@code ResolveSellerScopeUseCase}). 요청 파라미터로
 *       셀러를 받는 순간 이 서비스는 남의 이름으로 상품을 등록하는 API 가 된다.</li>
 * </ol>
 *
 * <p>{@code @Order(8)} 은 형제 모듈과 겹치지 않게 고른 값이다 — 1~4 는 operation-service,
 * 5~6 은 marketing-service, 7 은 partner-service 가 쓴다. 같은 순서 값이 둘이면 뜨는 순서에
 * 따라 한쪽 체인이 통째로 죽고, 그 사실은 요청이 엉뚱한 체인에 걸릴 때에야 드러난다.
 */
@Configuration
@EnableWebSecurity
@Import({JwtUtil.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
public class SellerSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SellerSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(8)
    // CSRF 비활성 경고(java:S4502) 억제 — STATELESS 토큰 API 다. 브라우저가 자동으로 실어 보내는
    // 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 교차 사이트 요청이 권한을 획득할 수 없다.
    // 세션·쿠키 인증을 도입하면 이 억제는 무효이며 CSRF 보호를 되살려야 한다.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain sellerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/seller/**")
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/seller/admin/**").hasRole("ADMIN")
                        // 경로를 명시하고 나머지를 denyAll 로 닫는다. securityMatcher 가 이미
                        // 같은 범위를 잡고 있어 결과는 같지만, 그러면 인가 결정이 matcher 안에
                        // 숨는다 — 나중에 matcher 가 넓어지면 아무 신호 없이 범위가 늘어난다.
                        .requestMatchers("/api/seller/**").authenticated()
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
