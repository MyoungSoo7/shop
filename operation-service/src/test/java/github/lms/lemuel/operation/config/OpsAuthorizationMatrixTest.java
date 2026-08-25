package github.lms.lemuel.operation.config;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/ops/**} 인가 규칙 회귀 가드 — 선언이 아니라 <b>판정</b>을 본다.
 *
 * <h2>왜 order-service 와 따로 필요한가</h2>
 * 두 체인은 성질이 반대다. order-service 의 {@code SecurityConfig} 는 포괄 매처 없이 경로를
 * 하나씩 열거해서 <b>빠뜨리면 샌다</b>. 반대로 여기는 {@code securityMatcher("/api/ops/**")}
 * 아래 {@code anyRequest().hasRole("ADMIN")} 이라 <b>새 경로가 기본으로 닫힌다</b>.
 *
 * <p>기본이 닫힘이라는 사실이 곧 검증이 필요 없다는 뜻은 아니다. 이 체인이 지키는 것은 한 줄
 * ({@code @Order(1)} + securityMatcher) 이고, 그 한 줄이 사라지거나 전역 체인이 앞으로 오면
 * {@code /api/ops/**} 는 조용히 <b>로그인만 하면 되는</b> 경로가 된다 — 매출·환불·인시던트가
 * 그 뒤에 있다. 컴파일도 통과하고 기존 테스트도 전부 초록인 종류의 변경이다.
 *
 * <p>웹훅 예외({@code permitAll})가 얼마나 넓은지도 여기서 고정한다. {@code /api/ops/webhook/**}
 * 는 역할이 아니라 공유 시크릿({@link OpsWebhookAuthFilter}) 이 지키므로, 이 접두어가 넓어지는
 * 순간 그만큼이 역할 검사 바깥으로 나간다.
 *
 * <h2>무엇을 어떻게 보는가</h2>
 * 실제 {@code springSecurityFilterChain} 에 요청을 흘려 상태코드로 판정을 읽는다
 * (설정이 미인증 401 · 권한부족 403 으로 고정한다). 프로브 컨트롤러가 모든 경로를 200 으로
 * 받으므로 "통과"와 "경로 없음(404)"이 섞이지 않는다.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        OpsAuthorizationMatrixTest.ProbeConfig.class,
        OperationSecurityConfig.class
})
class OpsAuthorizationMatrixTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    /**
     * 새로 추가된 추이 조회를 포함한 운영 콘솔 경로.
     *
     * <p>{@code /dashboard/trend} 는 매처를 새로 달지 않았다 — 포괄 규칙이 덮기 때문이다.
     * 그 "덮는다"가 사실인지는 주석이 아니라 이 케이스가 말해야 한다.
     */
    @ParameterizedTest(name = "USER → 403: {0}")
    @ValueSource(strings = {
            "/api/ops/dashboard/trend",
            "/api/ops/dashboard/today",
            "/api/ops/audit-logs"
    })
    void 일반_사용자는_403(String path) throws Exception {
        mvc.perform(get(path).with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MANAGER 도 못 본다 — 운영 콘솔은 ADMIN 전용이다")
    void 매니저도_403() throws Exception {
        mvc.perform(get("/api/ops/dashboard/trend").with(user("m").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    /**
     * 토큰이 아예 없으면 403 이 아니라 401 이다. 둘을 뭉뚱그리면 "권한이 없다"와
     * "인증 자체가 안 붙었다"를 구분하지 못해, 필터가 통째로 빠진 사고를 권한 문제로 오진한다.
     */
    @Test
    @DisplayName("미인증은 401")
    void 미인증은_401() throws Exception {
        mvc.perform(get("/api/ops/dashboard/trend"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN 은 통과한다 — 너무 좁게 잠겨 콘솔이 죽는 것도 회귀다")
    void ADMIN_은_통과한다() throws Exception {
        mvc.perform(get("/api/ops/dashboard/trend").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    /**
     * 웹훅만 역할 검사 밖이다. 토큰이 설정되지 않은 이 테스트 컨텍스트에서는
     * {@link OpsWebhookAuthFilter} 가 통과시키므로 200 이 뜬다 — 즉 여기서 보는 것은
     * "역할 없이도 들어온다"는 사실 하나다. 이 케이스가 403/401 로 바뀌면 permitAll 이
     * 사라진 것이고, Alertmanager 알람 수신이 조용히 끊긴다.
     */
    @Test
    @DisplayName("웹훅은 역할이 아니라 공유 시크릿이 지킨다")
    void 웹훅은_역할검사_밖이다() throws Exception {
        mvc.perform(post("/api/ops/webhook/alerts"))
                .andExpect(status().isOk());
    }

    /**
     * permitAll 예외가 웹훅 접두어에만 걸리는지 확인한다. {@code /api/ops/webhooks} 처럼
     * 한 글자 다른 경로까지 열리면 예외가 의도보다 넓은 것이다.
     */
    @Test
    @DisplayName("웹훅 예외는 접두어가 정확히 일치할 때만 — 인접 경로는 그대로 막힌다")
    void 웹훅_예외는_넓어지지_않는다() throws Exception {
        mvc.perform(post("/api/ops/webhooks").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    /**
     * 프로브 — 모든 경로를 200 으로 받아 인가 축만 남긴다.
     *
     * <p>{@code @EnableWebSecurity} 는 여기서 붙인다. {@link OperationSecurityConfig} 자체에는
     * 없고 앱에서는 shared-common 의 전역 설정이 켜 주기 때문이다 — 그 인프라를 테스트가
     * 대신 세운다. 이게 없으면 {@code HttpSecurity} 빈이 없어 체인이 아예 안 만들어진다.
     */
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class ProbeConfig {

        @Bean
        JwtProperties jwtProperties() {
            JwtProperties props = new JwtProperties();
            props.setIssuer("test");
            props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
            props.setTtlSeconds(3600);
            return props;
        }

        @Bean
        JwtUtil jwtUtil(JwtProperties props) {
            return new JwtUtil(props);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil) {
            return new JwtAuthenticationFilter(jwtUtil);
        }

        @Bean
        OpsProperties opsProperties() {
            return new OpsProperties();
        }

        @Bean
        OpsWebhookAuthFilter opsWebhookAuthFilter(OpsProperties properties) {
            // keyRequired=false — 토큰 미설정 시 통과(로컬/개발과 같은 시맨틱).
            // 여기서 검증하는 축은 웹훅 토큰이 아니라 "역할 검사를 거치는가" 다.
            return new OpsWebhookAuthFilter(properties, false);
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @RequestMapping("/**")
        String any() {
            return "ok";
        }
    }
}
