package github.lms.lemuel.common.config.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 규칙 회귀 가드 — {@link SecurityConfig} 의 <b>선언</b>이 아니라 <b>판정</b>을 검증한다.
 *
 * <h2>왜 필요한가</h2>
 * {@link SecurityConfigContextTest} 는 필터 체인이 <b>빌드되는지</b>만 본다. 그래서 매처 한 줄이
 * 지워져도, 더 넓은 매처가 위로 올라와 삼켜도 테스트는 전부 초록이었다. 이 설정에는 포괄
 * {@code /admin/**} 매처가 없고 경로를 하나씩 열거하는 방식이라, <b>빠뜨린 경로는 조용히
 * {@code anyRequest().authenticated()} 로 떨어진다</b> — 로그인만 하면 누구나 호출할 수 있다는 뜻이다.
 *
 * <p>그 사고는 이미 네 번 났고 {@code SecurityConfig} 주석에 남아 있다:
 * 쿠폰 생성(누구나 자기에게 100% 할인 쿠폰 발행) · VAN 진입점(사용자 토큰으로 카드 거래 위조) ·
 * 포인트 콘솔 · 보험 언더라이팅(청약 UUID 만 알면 계약 발행). 네 건 모두 <b>컴파일도 테스트도
 * 통과한 채</b> 운영에 들어갔다 — "매처가 없다"는 컴파일러가 볼 수 없는 축이다.
 *
 * <h2>무엇을 어떻게 보는가</h2>
 * 실제 {@code springSecurityFilterChain} 에 요청을 흘려 <b>상태코드로 판정을 읽는다</b>
 * ({@code SecurityConfig} 의 핸들러가 미인증 401 · 권한부족 403 으로 고정한다).
 * 프로브 컨트롤러가 모든 경로를 200 으로 받으므로, 403 이 아니면 인가를 통과한 것이다.
 *
 * <p>정적 검사(매처 문자열 grep)로는 <b>순서</b>를 볼 수 없어 이 방식을 골랐다.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        SecurityAuthorizationMatrixTest.ProbeConfig.class,
        SecurityConfig.class
})
@TestPropertySource(properties = "cors.origins=http://localhost:3000")
class SecurityAuthorizationMatrixTest {

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
     * 재무/자금흐름 리포트 — settlement `report` 슬라이스의 전 표면.
     *
     * <p>셀러별 거래액 랭킹과 셀러 이메일(원문, 마스킹 없음)이 실리는 응답이라 일반 사용자에게
     * 열리면 그대로 유출이다. Seed {@code settlement-service-report} KI-1·KI-7 이 가리키는 지점.
     */
    @Nested
    @DisplayName("/api/reports/** — ADMIN·MANAGER 전용 (Seed KI-7)")
    class Reports {

        @ParameterizedTest(name = "미인증 → 401: {0}")
        @ValueSource(strings = {
                "/api/reports/cashflow",
                "/api/reports/cashflow/pdf",
                "/api/reports/sellers/1/cashflow",
                "/api/reports/sales-stats/summary",
                "/api/reports/sales-stats/breakdown"
        })
        void 미인증은_401(String path) throws Exception {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "USER → 403: {0}")
        @ValueSource(strings = {
                "/api/reports/cashflow",
                "/api/reports/cashflow/pdf",
                "/api/reports/sellers/1/cashflow",
                "/api/reports/sales-stats/summary",
                "/api/reports/sales-stats/breakdown"
        })
        void 일반_사용자는_403(String path) throws Exception {
            mvc.perform(get(path).with(user("seller").roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "ADMIN → 통과: {0}")
        @ValueSource(strings = {
                "/api/reports/cashflow",
                "/api/reports/sales-stats/breakdown"
        })
        void 관리자는_통과(String path) throws Exception {
            mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk());
        }

        @ParameterizedTest(name = "MANAGER → 통과: {0}")
        @ValueSource(strings = {
                "/api/reports/cashflow",
                "/api/reports/sales-stats/breakdown"
        })
        void 매니저는_통과(String path) throws Exception {
            mvc.perform(get(path).with(user("manager").roles("MANAGER")))
                    .andExpect(status().isOk());
        }

        /**
         * 하위 경로를 새로 만들어도 와일드카드가 덮는지 — 경로가 늘 때 규칙을 다시 안 적어도 되는 근거.
         * 이 케이스가 깨지면 {@code /api/reports/**} 가 접두 매칭을 잃은 것이다.
         */
        @Test
        @DisplayName("아직 없는 하위 경로도 와일드카드가 덮는다")
        void 미래_하위경로도_보호된다() throws Exception {
            mvc.perform(get("/api/reports/does-not-exist-yet/deep/path")
                            .with(user("seller").roles("USER")))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * 같은 실패 모드를 공유하는 정산 계열 경로 — 리포트와 같은 등급으로 선언돼 있다.
     *
     * <p>여기 있는 경로가 열리면 리포트가 닫혀 있어도 같은 수치를 다른 문으로 꺼낼 수 있다.
     */
    @Nested
    @DisplayName("정산 계열 조회 — ADMIN·MANAGER 전용")
    class SettlementFamily {

        @ParameterizedTest(name = "USER → 403: {0}")
        @ValueSource(strings = {
                "/api/settlements/1",
                "/api/ledger/entries",
                "/api/account/trial-balance",
                "/settlements/1"
        })
        void 일반_사용자는_403(String path) throws Exception {
            mvc.perform(get(path).with(user("seller").roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "MANAGER → 통과: {0}")
        @ValueSource(strings = {
                "/api/settlements/1",
                "/api/ledger/entries",
                "/api/account/trial-balance"
        })
        void 매니저는_통과(String path) throws Exception {
            mvc.perform(get(path).with(user("manager").roles("MANAGER")))
                    .andExpect(status().isOk());
        }
    }

    /**
     * 과거에 실제로 새어 본 경로들 — {@link SecurityConfig} 주석이 사고로 기록한 네 건.
     *
     * <p>여기 있는 경로는 전부 "매처를 빠뜨려 {@code anyRequest().authenticated()} 로 떨어졌다"는
     * 같은 원인으로 열렸었다. 고친 뒤 다시 닫혔는지 지키는 것이 없어서 이 묶음을 남긴다 —
     * 한 번 난 사고는 같은 자리에서 다시 난다.
     */
    @Nested
    @DisplayName("과거 누출 이력 경로 — 재발 방지")
    class PreviouslyLeaked {

        @ParameterizedTest(name = "USER → 403: {0}")
        @ValueSource(strings = {
                "/coupons",                      // 쿠폰 조회·발행: 누구나 자기에게 100% 할인 쿠폰을 만들 수 있었다
                "/admin/coupons",
                "/admin/points/summary",         // 포인트 콘솔: 수기 지급은 없던 돈을 만든다
                "/admin/gift-cards",
                "/admin/payouts/1",              // 송금 실행
                "/admin/deposits",
                "/admin/expense-receipts"
        })
        void 일반_사용자는_403(String path) throws Exception {
            mvc.perform(get(path).with(user("u").roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("쿠폰 생성(POST /coupons)은 GET 과 별개로 막힌다")
        void 쿠폰_생성은_별도로_막힌다() throws Exception {
            mvc.perform(post("/coupons").with(user("u").roles("USER")).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("보험 언더라이팅 승인은 접수자 권한으로 못 부른다")
        void 언더라이팅_승인은_백오피스_전용() throws Exception {
            mvc.perform(post("/api/insurance/applications/1/approve")
                            .with(user("fc").roles("USER")).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        /**
         * VAN 진입점은 <b>역할이 아니라 공유 시크릿</b>이 지킨다(사람이 아니라 기계라서).
         * 그래서 사용자 토큰으로는 403 이 아니라 <b>401</b> 이 떨어진다 —
         * {@code permitAll} 뒤에서 {@link InternalApiKeyFilter} 가 막는 구조다.
         * 이 케이스가 403 으로 바뀌면 시크릿 필터가 아니라 역할로 문을 연 것이니 설계가 바뀐 것이다.
         */
        @Test
        @DisplayName("VAN 진입점은 사용자 토큰이 아니라 공유 시크릿이 막는다(401)")
        void VAN_은_공유시크릿이_막는다() throws Exception {
            mvc.perform(post("/van/v1/authorizations").with(user("u").roles("USER")).with(csrf()))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * 프로브 — 모든 경로를 200 으로 받는다.
     *
     * <p>핸들러가 없으면 인가를 통과한 요청이 404 로 떨어져 "통과"와 "경로 없음"이 섞인다.
     * 여기서는 <b>인가 판정만</b> 보고 싶으므로 catch-all 로 그 축을 지운다.
     */
    @Configuration
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
        InternalApiKeyFilter internalApiKeyFilter() {
            return new InternalApiKeyFilter("test-internal-key");
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
