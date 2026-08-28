package github.lms.lemuel.marketing.config;

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
 * 프로모션 경로 보안.
 *
 * <p>체인이 둘인 이유는 두 경로의 성격이 다르기 때문이다.
 *
 * <ul>
 *   <li>{@code /admin/promotions/**} — 운영 도구. 전부 ROLE_ADMIN.</li>
 *   <li>{@code /api/promotions/**} — 고객 화면. 목록·현황 조회는 비로그인도 본다(배너를 봐야
 *       로그인할 마음이 생긴다). 참여(POST)는 반드시 인증이다 — 참여 주체가 없으면 누구의
 *       출석인지 기록할 수 없다.</li>
 * </ul>
 *
 * <p>참여 주체를 요청 본문에서 받지 않는 것이 핵심이다. 레거시는 화면이 보낸 회원번호를 그대로
 * 믿어서, 남의 회원번호로 출석과 당첨 포인트를 대신 받을 수 있었다. 여기서는 JWT 에서만 꺼낸다
 * ({@code CurrentMember}).
 *
 * <p>루트 스캔이라 shared-common 의 전역 체인도 함께 뜬다. 그 체인은 순서가 없어 최후순이므로,
 * 이 둘을 {@code @Order} 로 앞에 세워 프로모션 경로만 가로챈다. 5·6 은 operation-service 가 쓰는
 * 1~4 와 겹치지 않게 고른 값이다 — 같은 순서 값이 둘이면 뜨는 순서에 따라 한쪽이 통째로 죽는다.
 */
@Configuration
@EnableWebSecurity
@Import({JwtUtil.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
public class MarketingSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public MarketingSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(5)
    // CSRF 비활성 경고(java:S4502) 억제 — STATELESS 토큰 API 다. 브라우저가 자동으로 실어 보내는
    // 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 교차 사이트 요청이 권한을 획득할 수 없다.
    // 세션·쿠키 인증을 도입하면 이 억제는 무효이며 CSRF 보호를 되살려야 한다.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain marketingAdminSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/admin/promotions/**")
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 경로를 명시하고 나머지를 denyAll 로 닫는다. `anyRequest().hasRole("ADMIN")`
                        // 한 줄로도 결과는 같지만, 그러면 인가가 securityMatcher 안에 숨는다 —
                        // 나중에 이 체인의 securityMatcher 가 넓어지면 아무 신호 없이 범위가 늘어나고,
                        // security-matcher-gate 도 경로별 결정을 읽지 못해 "인가 출처 없음"으로 본다.
                        // 열린 경로를 적고 나머지를 거절하는 쪽이 사람에게도 기계에게도 읽힌다.
                        .requestMatchers("/admin/promotions/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(6)
    @SuppressWarnings("java:S4502")
    SecurityFilterChain marketingApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/promotions/**")
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 진행 중 이벤트 목록만 비로그인에 연다 — 배너를 봐야 로그인할 마음이 생긴다.
                        // 출석판·럭키박스 현황은 "내" 참여 상태라서 주체가 없으면 의미가 없다.
                        .requestMatchers(HttpMethod.GET, "/api/promotions").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
