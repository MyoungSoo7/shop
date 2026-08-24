package github.lms.lemuel.operation.notification.adapter.in.web;

import github.lms.lemuel.operation.notification.application.port.out.NotificationStream;
import github.lms.lemuel.operation.notification.application.port.out.StreamSubscription;
import github.lms.lemuel.operation.notification.domain.StreamEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 브라우저용 푸시 엔드포인트: {@code GET /api/notifications/stream} (SSE).
 *
 * <p>HTTP 커넥션 1개 == 허브 구독 1개. 클라이언트 신원은 JWT 에서 오고, 재접속 시 브라우저가
 * {@code Last-Event-ID} 를 자동으로 재전송하면 허브가 놓친 것을 보존 창 범위에서 다시 보낸다.
 *
 * <p>하트비트 주석이 유휴 커넥션을 살려 둔다 — 프록시는 1분간 조용한 커넥션을 태연히 죽이고,
 * 죽은 상대는 그러지 않으면 다음 실제 이벤트에서야 발각된다.
 *
 * <p><b>경로 주의</b>: 이 컨트롤러만 {@code /api/notifications} 아래에 둔다. 발송/데모 경로
 * ({@link NotificationController})는 {@code /internal/notifications} 라 게이트웨이에 노출되지 않는다.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationStreamController {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamController.class);

    /** 표준 SSE 재개 헤더 — EventSource 가 자동으로 재전송한다. */
    public static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final String EVENT_NAME = "notification";

    private final NotificationStream stream;
    private final JwtSubscriberIdentityResolver identities;
    private final long timeoutMs;
    private final long reconnectHintMs;

    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationStreamController(NotificationStream stream,
                                        JwtSubscriberIdentityResolver identities,
                                        @Value("${app.stream.timeout-ms:1800000}") long timeoutMs,
                                        @Value("${app.stream.heartbeat-seconds:15}") long heartbeatSeconds,
                                        @Value("${app.stream.reconnect-hint-ms:2000}") long reconnectHintMs) {
        this.stream = stream;
        this.identities = identities;
        this.timeoutMs = timeoutMs;
        this.reconnectHintMs = reconnectHintMs;
        if (heartbeatSeconds > 0) {
            heartbeats.scheduleAtFixedRate(this::pingAll, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        }
    }

    // charset 을 명시한다: SSE 는 규격상 UTF-8 이지만, 수식 없는 text/event-stream 은 중간 장비(와
    // 비브라우저 클라이언트)가 추측하게 만들고, 추측이 latin-1 로 떨어지면 한글 제목이 깨져 도착한다.
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter stream(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "token", required = false) String tokenParam,
            @RequestHeader(name = LAST_EVENT_ID, required = false) String lastEventIdHeader,
            @RequestParam(name = "lastEventId", required = false) String lastEventIdParam) {

        if (!identities.isConfigured()) {
            throw new StreamNotConfiguredException(
                    "notification push stream requires app.jwt.secret (>= 32 bytes)");
        }
        SubscriberIdentity identity = identities.resolve(
                JwtSubscriberIdentityResolver.tokenFrom(authorization, tokenParam));
        if (identity == null) {
            throw new StreamUnauthorizedException("a valid JWT is required to open the notification stream");
        }

        Connection connection = new Connection(new SseEmitter(timeoutMs));
        connections.add(connection);

        // 먼저 바이트를 보낸다: EventSource 는 무언가 도착해야만 onopen 을 발화하고,
        // retry 힌트는 브라우저 기본값 대신 우리가 정한 재접속 백오프를 고정한다.
        connection.send(SseEmitter.event().comment("connected").reconnectTime(reconnectHintMs));

        connection.subscription = stream.subscribe(
                identity.recipients(),
                parseLastEventId(lastEventIdHeader, lastEventIdParam),
                event -> connection.send(SseEmitter.event()
                        .id(String.valueOf(event.seq()))
                        .name(EVENT_NAME)
                        .data(StreamEventDto.from(event), MediaType.APPLICATION_JSON)));

        connection.emitter.onCompletion(() -> close(connection));
        connection.emitter.onTimeout(() -> close(connection));
        connection.emitter.onError(e -> close(connection));

        log.debug("stream opened subject={} identities={}", identity.subject(), identity.recipients());
        return connection.emitter;
    }

    /**
     * 깨진 재개 지점은 요청을 실패시키는 대신 "라이브만"으로 격하한다 — 잘못 저장된 id 를 쥔
     * 클라이언트가 영원히 에러로 재접속하는 상태에 갇히지 않게 한다.
     */
    static Long parseLastEventId(String header, String param) {
        String raw = firstNonBlank(header, param);
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw);
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    private void pingAll() {
        for (Connection connection : connections) {
            try {
                connection.send(SseEmitter.event().comment("ping"));
            } catch (Exception ignored) {
                // send() 가 이미 커넥션을 정리했다 — 하트비트 루프는 계속 돈다.
            }
        }
    }

    private void close(Connection connection) {
        if (connections.remove(connection) && connection.subscription != null) {
            connection.subscription.cancel();
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
        for (Connection connection : List.copyOf(connections)) {
            close(connection);
            try {
                connection.emitter.complete();
            } catch (Exception ignored) {
                // 이미 완료·끊긴 커넥션 — 종료 경로에서 시끄러울 이유가 없다.
            }
        }
    }

    /** 살아 있는 SSE 커넥션 수 — 테스트·메트릭용. */
    int connectionCount() {
        return connections.size();
    }

    /**
     * 열린 SSE 커넥션 1개. 전송은 직렬화된다: 발행 스레드와 하트비트 스레드가 한 emitter 위에서
     * 프레임을 교차시키면 SseEmitter 가 견디지 못한다.
     */
    private final class Connection {
        private final SseEmitter emitter;
        private final Object writeLock = new Object();
        private volatile StreamSubscription subscription;

        private Connection(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void send(SseEmitter.SseEventBuilder event) {
            synchronized (writeLock) {
                try {
                    emitter.send(event);
                } catch (Exception e) {
                    // 클라이언트가 사라졌거나(닫힌 탭·죽은 프록시) emitter 가 이미 완료됐다.
                    // 구독을 걷어내고 호출자(허브)가 이 리스너를 떨구게 예외를 올린다.
                    close(this);
                    throw new IllegalStateException("sse send failed", e);
                }
            }
        }
    }
}
