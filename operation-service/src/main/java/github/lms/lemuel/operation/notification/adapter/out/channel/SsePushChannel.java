package github.lms.lemuel.operation.notification.adapter.out.channel;

import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.application.port.out.NotificationStream;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 수신자의 열린 브라우저 커넥션(SSE)으로 알림을 밀어 넣는 전달 채널 — 이메일·슬랙의 인앱 짝.
 *
 * <p>항상 활성이다: SMTP·웹훅과 달리 설정할 것이 없고, 듣는 사람이 없는 발행은 실패가 아니다 —
 * 이벤트는 보존 창에 들어가므로 {@code Last-Event-ID} 로 재접속한 클라이언트가 여전히 받아 간다.
 */
@Component
public class SsePushChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SsePushChannel.class);

    private final NotificationStream stream;

    public SsePushChannel(NotificationStream stream) {
        this.stream = stream;
    }

    @Override
    public String name() {
        return "sse";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void send(Notification notification) {
        StreamEvent event = stream.publish(notification);
        log.debug("pushed seq={} to recipient={}", event.seq(), event.recipient());
    }
}
