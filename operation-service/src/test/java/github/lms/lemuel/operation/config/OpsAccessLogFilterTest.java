package github.lms.lemuel.operation.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 콘솔 접근 로그의 계약.
 *
 * <p>이 필터가 지키려는 것은 두 가지다. 첫째, <b>거부된 접근이 남는다</b> — 권한 없는 사람이
 * 관리자 API 를 두드린 사실은 성공한 조회보다 더 알아야 하는 사건인데, 체인 밖에서는 보이지 않는다.
 * 둘째, <b>남기면서 개인정보를 흘리지 않는다</b> — 접근 로그는 열람 범위가 넓은 곳으로 흐른다.
 */
class OpsAccessLogFilterTest {

    private static final String LOGGER_NAME = "ops.access";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        SecurityContextHolder.clearContext();
    }

    private String logLineFor(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new OpsAccessLogFilter().doFilter(request, response, chain);
        assertThat(appender.list).as("접근 한 건은 정확히 한 줄로 남아야 한다").hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("개인정보 자리의 파라미터 값은 이름을 보고 가린다")
    void parameterValuesInPrivacyPositionsAreMaskedByName() {
        // 값 패턴 기반 마스킹(PIIMaskingConverter)은 한국어 이름·주소처럼 형태가 없는 값을 못 잡는다.
        // 파라미터는 이름이 무엇인지 우리가 알고 있으므로, 값을 뜯어보는 대신 이름으로 판정한다.
        String masked = OpsAccessLogFilter.maskedQueryString(
                "actorEmail=seller@lemuel.co.kr&phone=010-1234-5678&addr=%EC%84%9C%EC%9A%B8"
                        + "&action=BOARD_DELETED&page=0");

        assertThat(masked).isEqualTo("?actorEmail=****&phone=****&addr=****&action=BOARD_DELETED&page=0");
    }

    @Test
    @DisplayName("가릴 이유가 없는 조회 조건은 그대로 남는다")
    void ordinarySearchConditionsSurviveIntact() {
        // 전부 가려버리면 로그가 남아도 무슨 조회였는지 알 수 없어 존재 이유가 사라진다.
        assertThat(OpsAccessLogFilter.maskedQueryString("from=2026-08-01&to=2026-08-28&size=50"))
                .isEqualTo("?from=2026-08-01&to=2026-08-28&size=50");
        assertThat(OpsAccessLogFilter.maskedQueryString(null)).isEmpty();
        assertThat(OpsAccessLogFilter.maskedQueryString("")).isEmpty();
        // 값 없는 플래그는 가릴 것이 없으므로 이름만 그대로 둔다(잘라내면 조건이 사라진 것처럼 보인다).
        assertThat(OpsAccessLogFilter.maskedQueryString("verbose")).isEqualTo("?verbose");
    }

    @Test
    @DisplayName("대소문자·접두어가 붙어도 개인정보 자리를 놓치지 않는다")
    void privacyFieldDetectionSurvivesNamingVariants() {
        assertThat(OpsAccessLogFilter.isPrivacyField("actorEmail")).isTrue();
        assertThat(OpsAccessLogFilter.isPrivacyField("MOBILE")).isTrue();
        assertThat(OpsAccessLogFilter.isPrivacyField("card_no")).isTrue();
        assertThat(OpsAccessLogFilter.isPrivacyField("refreshToken")).isTrue();
        assertThat(OpsAccessLogFilter.isPrivacyField("action")).isFalse();
        assertThat(OpsAccessLogFilter.isPrivacyField("")).isFalse();
        assertThat(OpsAccessLogFilter.isPrivacyField(null)).isFalse();
    }

    @Test
    @DisplayName("인가에서 거부된 요청도 상태 코드와 함께 남는다")
    void rejectedRequestsAreLoggedWithTheirStatus() throws Exception {
        // 이 필터가 존재하는 첫 번째 이유. 401·403 은 보안 체인 안에서 끝나므로, 체인 밖의 어떤
        // 로깅에도 잡히지 않는다 — 관리자 API 를 두드린 시도가 통째로 보이지 않게 된다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ops/audit-logs");
        FilterChain denying = (req, res) ->
                ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_FORBIDDEN);

        String line = logLineFor(request, denying);

        assertThat(line).contains("status=403").contains("/api/ops/audit-logs").contains("actor=anonymous");
    }

    @Test
    @DisplayName("인증된 요청은 행위자와 함께 남는다")
    void authenticatedRequestsCarryTheActor() throws Exception {
        // 행위자는 사용자가 보낸 입력이 아니라 토큰이 증명한 값이라 가리지 않는다.
        // 누가 했는지를 지우면 접근 로그가 존재할 이유가 사라진다.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@lemuel.co.kr", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ops/audit-logs/export");
        request.setQueryString("actorEmail=seller@lemuel.co.kr&from=2026-08-01");

        String line = logLineFor(request, new MockFilterChain());

        assertThat(line).contains("actor=admin@lemuel.co.kr");
        assertThat(line).contains("actorEmail=****");
        assertThat(line).doesNotContain("seller@lemuel.co.kr");
    }

    @Test
    @DisplayName("헤더로 들어온 클라이언트 IP 는 로그 행을 위조하지 못한다")
    void forwardedClientIpCannotForgeLogLines() throws Exception {
        // X-Forwarded-For 는 클라이언트가 마음대로 채우는 값이다. 줄바꿈이 통과하면 가짜 접근
        // 기록을 통째로 지어낼 수 있고, 그때부터 이 로그는 증적으로 쓸 수 없다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ops/audit-logs");
        request.addHeader("X-Forwarded-For", "203.0.113.7\n[OPS] GET /fake actor=someone-else, 10.0.0.1");

        String line = logLineFor(request, new MockFilterChain());

        assertThat(line).contains("ip=203.0.113.7");
        assertThat(line).doesNotContain("\n");
        assertThat(line).doesNotContain("someone-else");
    }

    @Test
    @DisplayName("아래 필터가 터져도 접근 기록은 남고 예외는 그대로 올라간다")
    void aFailingChainStillLeavesATraceAndDoesNotSwallowTheError() {
        // 로깅이 예외를 삼키면 장애가 조용해지고, 로깅을 건너뛰면 장애 순간의 접근만 기록이 빈다.
        // 둘 다 아니어야 한다.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ops/boards");
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("kaboom");
        };

        assertThatThrownBy(() -> new OpsAccessLogFilter()
                .doFilter(request, new MockHttpServletResponse(), exploding))
                .isInstanceOf(IllegalStateException.class);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("POST").contains("/api/ops/boards");
    }

    @Test
    @DisplayName("가림 대상 목록이 비어 있지 않다")
    void thePrivacyFieldListIsNotEmpty() {
        // 목록이 비면 위의 마스킹 검증들은 전부 통과하면서 아무것도 가리지 않는 상태가 된다.
        // 통과를 근거로 쓰려면 검사 대상이 실제로 있었다는 것부터 고정해야 한다.
        assertThat(List.of("password", "email", "phone", "card"))
                .allMatch(OpsAccessLogFilter::isPrivacyField);
    }
}
