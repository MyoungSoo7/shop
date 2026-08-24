package github.lms.lemuel.operation.board.config;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * board-service 자체 최소 보안 설정.
 *
 * <p>스캔을 board 패키지로 한정했으므로 shared-common 의 전역 {@code SecurityConfig} 는 잡히지
 * 않는다. JWT <b>검증 빈만</b> 명시적으로 물어 자체 체인을 구성한다(company-service 와 동일 방식).
 *
 * <p>경로 등급은 두 개뿐이다:
 * <ul>
 *   <li>{@code GET /api/boards/**} — 필터 단계는 열어 둔다. 공개 게시판(읽기 역할 미지정)이
 *       존재하고 비로그인 방문자가 읽어야 하기 때문이다. <b>어떤 게시판이 보이는지는 도메인이
 *       정한다</b> — 컨트롤러가 {@code BoardDefinition.canRead(role)} 로 걸러 응답한다.
 *       필터에서 막으면 "공개 게시판도 401" 이 되고, 도메인 판정을 필터로 옮기면 정책이 두 곳에 생긴다.</li>
 *   <li>{@code /admin/boards/**} — 게시판을 만들고 정책을 바꾸는 경로. 게시판 하나가 곧 화면
 *       하나이므로 화면 구성 변경 권한과 같은 등급(ADMIN)으로 막는다.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@Import({JwtUtil.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
public class BoardSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public BoardSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(3)
    // CSRF 비활성 경고(java:S4502) 억제 — 세션을 만들지 않는(STATELESS) 토큰 API 라 브라우저가
    // 자동으로 실어 보내는 자격증명을 인증에 쓰지 않는다. CSRF 가 방어할 대상 자체가 없다.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain boardSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/boards/**", "/admin/boards/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/error").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        // 게시판 관리 — 게시판 하나가 곧 화면 하나다.
                        .requestMatchers("/admin/boards/**").hasRole("ADMIN")
                        // 공개 조회(가시성 판정은 도메인 accessPolicy 가 한다 — 클래스 javadoc 참조)
                        .requestMatchers(HttpMethod.GET, "/api/boards/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
