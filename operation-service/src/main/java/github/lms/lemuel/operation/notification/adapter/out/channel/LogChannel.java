package github.lms.lemuel.operation.notification.adapter.out.channel;

import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 항상 켜져 있는 기본 채널. 외부 의존이 0 이라 이 슬라이스는 어떤 환경에서도 시연 가능하다
 * (Slack·SMTP 자격증명이 없어도 알림이 흔적 없이 사라지지 않는다).
 */
@Component
public class LogChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger("notification.channel.log");

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void send(Notification notification) {
        log.info("NOTIFY {}", NotificationTemplate.renderPlainText(notification));
    }
}
