package github.lms.lemuel.operation.notification.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.notification.adapter.out.channel.LogChannel;
import github.lms.lemuel.operation.notification.adapter.out.dedupe.InMemoryTtlDedupeStore;
import github.lms.lemuel.operation.notification.adapter.out.stream.InMemoryNotificationStream;
import github.lms.lemuel.operation.notification.application.port.out.NotificationStream;
import github.lms.lemuel.operation.notification.application.service.NotificationDispatcher;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 웹 표면의 HTTP 계약. standalone MockMvc 로 컨트롤러 + 어드바이스만 세워, DB·브로커 없이
 * 상태코드 매핑을 고정한다.
 *
 * <p>이관 시 <b>경로가 바뀌었다</b>: 스트림은 게이트웨이 계약을 지키려고 {@code /api/notifications/stream}
 * 그대로지만, 발송·데모는 {@code /internal/notifications/**} 로 옮겼다(외부 노출 금지 성질 보존).
 * 그 사실 자체를 여기서 못 박는다.
 */
class NotificationWebTest {

    private static final String SECRET = "notification-slice-test-secret-32bytes+";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationStream stream = new InMemoryNotificationStream();
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher(java.util.List.of(new LogChannel()), new InMemoryTtlDedupeStore());

    @AfterEach
    void closeDispatcher() {
        dispatcher.close();
    }

    private MockMvc mockMvc(String secret) {
        JwtSubscriberIdentityResolver resolver = new JwtSubscriberIdentityResolver(secret);
        return MockMvcBuilders
                .standaloneSetup(
                        new NotificationController(dispatcher),
                        new NotificationStreamController(stream, resolver, 30_000L, 0L, 2_000L))
                .setControllerAdvice(new NotificationExceptionHandler())
                .build();
    }

    private static String validToken() {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("seller@lemuel.co.kr")
                .claim("role", "USER")
                .claim("uid", 42L)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(KEY)
                .compact();
    }

    // --- 발송/데모 (internal) -------------------------------------------------

    @Test
    @DisplayName("데모는 활성 채널로 팬아웃하고 채널별 결과를 준다")
    void demoDispatchesThroughEnabledChannels() throws Exception {
        mockMvc(SECRET).perform(get("/internal/notifications/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduped").value(false))
                // LogChannel 은 항상 활성 → 결과가 최소 하나 있고 SUCCESS.
                .andExpect(jsonPath("$.results[?(@.channel == 'log')].status").value("SUCCESS"))
                .andExpect(jsonPath("$.allSucceeded").value(true));
    }

    @Test
    @DisplayName("공백 수신자는 500 이 아니라 400 이다")
    void blankRecipientIsRejectedAs400NotAs500() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", " ");
        body.put("subject", "s");
        body.put("body", "b");

        mockMvc(SECRET).perform(post("/internal/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("발송은 바디를 받아 디스패치한다")
    void sendAcceptsABodyAndDispatches() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "PAYMENT_CONFIRMED");
        body.put("recipient", "user@lemuel.co.kr");
        body.put("subject", "결제 완료");
        body.put("body", "결제가 확인되었습니다.");
        body.put("eventId", "web-evt-1");

        mockMvc(SECRET).perform(post("/internal/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[?(@.channel == 'log')].status").value("SUCCESS"));
    }

    @Test
    @DisplayName("같은 eventId 로 두 번 보내면 두 번째는 중복으로 스킵된다")
    void repeatedEventIdIsDeduped() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", "user@lemuel.co.kr");
        body.put("subject", "s");
        body.put("body", "b");
        body.put("eventId", "web-evt-dup");
        String json = objectMapper.writeValueAsString(body);
        MockMvc mvc = mockMvc(SECRET);

        mvc.perform(post("/internal/notifications/send")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());

        mvc.perform(post("/internal/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduped").value(true));
    }

    // --- 스트림 (api) --------------------------------------------------------

    @Test
    @DisplayName("토큰 없는 스트림 요청은 401 이다 — 500 이 아니다")
    void streamWithoutTokenIs401() throws Exception {
        mockMvc(SECRET).perform(get("/api/notifications/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                // 실패 사유는 응답에 싣지 않는다(정보 노출).
                .andExpect(jsonPath("$.message").value("authentication required"));
    }

    @Test
    @DisplayName("깨진 토큰도 401 이다")
    void streamWithGarbageTokenIs401() throws Exception {
        mockMvc(SECRET).perform(get("/api/notifications/stream").param("token", "not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("시크릿 미설정이면 스트림만 503 으로 꺼진다 — fail-closed")
    void streamIsUnavailableWhenNoSecretConfigured() throws Exception {
        mockMvc("").perform(get("/api/notifications/stream").param("token", validToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("STREAM_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("시크릿이 없어도 발송 경로는 계속 서빙한다 — 스트림만 꺼진다")
    void sendStillWorksWhenStreamIsUnconfigured() throws Exception {
        mockMvc("").perform(get("/internal/notifications/demo"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("유효 토큰은 SSE 커넥션을 연다(쿼리 파라미터 — EventSource 가 헤더를 못 쓴다)")
    void validTokenOpensTheStream() throws Exception {
        mockMvc(SECRET).perform(get("/api/notifications/stream").param("token", validToken()))
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("Authorization 헤더로도 스트림을 연다(비브라우저 클라이언트)")
    void authorizationHeaderAlsoOpensTheStream() throws Exception {
        mockMvc(SECRET).perform(get("/api/notifications/stream")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("깨진 Last-Event-ID 는 요청을 실패시키지 않고 라이브만으로 격하된다")
    void malformedLastEventIdDegradesToLiveOnly() throws Exception {
        mockMvc(SECRET).perform(get("/api/notifications/stream")
                        .param("token", validToken())
                        .header(NotificationStreamController.LAST_EVENT_ID, "not-a-number"))
                .andExpect(request().asyncStarted());

        // 파서 자체의 계약도 함께 고정한다.
        org.junit.jupiter.api.Assertions.assertNull(
                NotificationStreamController.parseLastEventId("not-a-number", null));
        org.junit.jupiter.api.Assertions.assertNull(
                NotificationStreamController.parseLastEventId("-5", null));
        org.junit.jupiter.api.Assertions.assertEquals(7L,
                NotificationStreamController.parseLastEventId("7", null));
        // 헤더가 비면 쿼리 파라미터로 폴백한다.
        org.junit.jupiter.api.Assertions.assertEquals(9L,
                NotificationStreamController.parseLastEventId(" ", "9"));
        org.junit.jupiter.api.Assertions.assertNull(
                NotificationStreamController.parseLastEventId(null, null));
    }
}
