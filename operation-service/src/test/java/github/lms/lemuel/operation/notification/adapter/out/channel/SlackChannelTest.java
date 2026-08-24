package github.lms.lemuel.operation.notification.adapter.out.channel;

import com.sun.net.httpserver.HttpServer;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 루프백 실서버를 상대로 한 Slack 채널 테스트.
 *
 * <p>목킹된 HTTP 클라이언트로는 보이지 않는 두 가지가 중요하다: 제목에 든 따옴표·개행이 JSON 을
 * 깨뜨리지 않아야 하고, 2xx 가 아닌 응답이 <b>던져진 실패</b>가 되어 디스패처가 셀 수 있어야 한다 —
 * 500 을 조용히 삼키는 웹훅은 아무것도 전달하지 않으면서 성공을 보고하는 채널이다.
 */
class SlackChannelTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private int status = 200;

    private String startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = (status >= 200 && status <= 299) ? "ok" : "boom";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static Notification notification() {
        return notification("정산 확정", "본문");
    }

    private static Notification notification(String subject, String body) {
        return new Notification(NotificationType.GENERIC, "seller-1", subject, body, null);
    }

    @Test
    @DisplayName("웹훅 URL 이 설정되기 전까지 채널은 비활성이다")
    void channelIsDisabledUntilWebhookUrlIsConfigured() {
        // URL 없음은 "배선 안 됨"이어야지 "배선됐는데 매 발송 실패"가 아니다.
        assertFalse(new SlackChannel("").isEnabled());
        assertFalse(new SlackChannel("   ").isEnabled());
        assertFalse(new SlackChannel(null).isEnabled());
        assertTrue(new SlackChannel("https://hooks.example/x").isEnabled());
        assertEquals("slack", new SlackChannel("").name());
    }

    @Test
    @DisplayName("성공 전송은 렌더된 텍스트를 유효한 JSON 으로 싣는다")
    void successfulPostCarriesRenderedTextAsValidJson() throws Exception {
        String url = startServer();

        new SlackChannel(url).send(notification());

        assertEquals(1, received.size());
        String payload = received.getFirst();
        assertTrue(payload.startsWith("{\"text\":\""), "예상 밖 페이로드: " + payload);
        assertTrue(payload.contains("정산 확정"), "제목 누락: " + payload);
    }

    @Test
    @DisplayName("따옴표·개행·백슬래시는 JSON 을 깨뜨리는 대신 이스케이프된다")
    void quotesNewlinesAndBackslashesAreEscaped() throws Exception {
        String url = startServer();

        new SlackChannel(url).send(notification("a\"b\\c", "line1\nline2\tend\r"));

        String payload = received.getFirst();
        assertTrue(payload.contains("\\\"b\\\\c"), "따옴표/백슬래시 미이스케이프: " + payload);
        assertTrue(payload.contains("\\n"), "개행 미이스케이프: " + payload);
        assertTrue(payload.contains("\\t"), "탭 미이스케이프: " + payload);
        assertTrue(payload.contains("\\r"), "캐리지리턴 미이스케이프: " + payload);
        // JSON 문자열 안의 날 개행은 상대편에서 파싱 에러다.
        assertFalse(payload.contains("\n"), "페이로드에 날 개행이 남아 있다: " + payload);
    }

    @Test
    @DisplayName("2xx 가 아닌 응답은 삼켜지지 않고 전송을 실패시킨다")
    void nonSuccessAnswerFailsTheSend() throws Exception {
        status = 500;
        String url = startServer();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new SlackChannel(url).send(notification()));

        assertTrue(error.getMessage().contains("500"), "상태코드가 메시지에 있어야 한다: " + error.getMessage());
        assertTrue(error.getMessage().contains("boom"), "응답 본문이 메시지에 있어야 한다: " + error.getMessage());
    }

    @Test
    @DisplayName("닿지 않는 웹훅은 전송을 실패시킨다")
    void unreachableWebhookFailsTheSend() {
        // 루프백 1번 포트는 즉시 거부한다 — 타임아웃을 기다리지 않는다.
        assertThrows(Exception.class,
                () -> new SlackChannel("http://127.0.0.1:1/hook").send(notification()));
    }

    @Test
    @DisplayName("제어문자가 없는 평범한 문자열은 그대로 실린다")
    void plainTextPassesThroughUnchanged() {
        assertEquals("\"hello world\"", SlackChannel.jsonString("hello world"));
    }
}
