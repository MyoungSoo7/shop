package github.lms.lemuel.operation.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code /api/notifications/**} 전용 보안 체인.
 *
 * <h2>왜 permitAll 인가 — 인증을 포기한 것이 아니다</h2>
 * 브라우저 {@code EventSource} 는 <b>요청 헤더를 설정할 수 없다</b>. 그래서 SSE 커넥션의 토큰은
 * {@code ?token=} 쿼리 파라미터로 온다. shared-common 의 전역 체인은 {@code Authorization} 헤더만
 * 보고 마지막에 {@code anyRequest().authenticated()} 로 떨어지므로, 이 경로를 그 체인에 맡기면
 * 브라우저는 <b>영구히 401</b> 이다(컨트롤러에 닿지도 못한다).
 *
 * <p>대신 인증 책임을 컨트롤러 앞단의 {@code JwtSubscriberIdentityResolver} 가 진다 —
 * 같은 HS256 시크릿({@code app.jwt.secret})으로 서명을 검증하고, 수신 신원을 <b>클레임에서만</b>
 * 파생한다(요청 파라미터에서 파생하는 순간 IDOR). 검증 실패는 401, 시크릿 미설정은 503 이며
 * 이는 fail-closed 다. 즉 게이트가 사라진 것이 아니라 <b>헤더를 못 쓰는 매체 때문에 위치가 옮겨진</b> 것이다.
 *
 * <p>{@code @Order(2)} — 운영 콘솔 체인({@code /api/ops/**}, Order 1)과 경로가 겹치지 않으므로
 * 순서 자체는 무해하지만, 전역 체인(순서 미지정 = 최후순)보다는 반드시 앞서야 한다.
 *
 * <p><b>발송·데모 경로는 여기 없다</b>: {@code NotificationController} 는 {@code /internal/notifications}
 * 아래에 있어 전역 체인의 {@code /internal/**} + 공유 시크릿 필터가 게이팅한다. 게이트웨이도
 * {@code /api/notifications/stream} 만 라우팅하므로 외부에서 발송 경로에 닿을 수 없다.
 */
@Configuration
public class NotificationSecurityConfig {

    @Bean
    @Order(2)
    // CSRF 비활성 경고(java:S4502) 억제 — 세션을 만들지 않는(STATELESS) 토큰 API 이고, 브라우저가
    // 자동으로 실어 보내는 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 CSRF 가 방어할 대상이 없다.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain notificationSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/notifications/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 인증은 JwtSubscriberIdentityResolver 가 한다(위 javadoc). 여기서 막으면
                // 쿼리 파라미터 토큰을 든 브라우저가 컨트롤러에 닿지 못한다.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
