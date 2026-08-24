package github.lms.lemuel.operation.notification.adapter.out.channel;

import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SMTP 로 이메일을 보낸다. {@code MAIL_USERNAME} + {@code MAIL_PASSWORD} 가 모두 있을 때만 활성.
 *
 * <p>jakarta.mail 을 직접 쓴다. 활성 경로는 설정으로 검증하고, 단위 테스트는 비활성·조립 경로와
 * 팬아웃 계약을 fake 로 덮으므로 살아 있는 SMTP 서버가 필요 없다.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger("notification.channel.email");

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;

    public EmailChannel(@Value("${app.channels.email.host:smtp.gmail.com}") String host,
                        @Value("${app.channels.email.port:587}") int port,
                        @Value("${app.channels.email.username:}") String username,
                        @Value("${app.channels.email.password:}") String password,
                        @Value("${app.channels.email.from:no-reply@lemuel.co.kr}") String from) {
        this.host = host;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.from = from;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean isEnabled() {
        return !username.isBlank() && !password.isBlank();
    }

    @Override
    public void send(Notification notification) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(notification.recipient()));
        message.setSubject(notification.subject());
        message.setText(notification.body());
        Transport.send(message);

        log.debug("email delivered to={}", notification.recipient());
    }
}
