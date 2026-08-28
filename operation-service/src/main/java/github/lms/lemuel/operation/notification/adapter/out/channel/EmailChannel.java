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
 *
 * <p><b>타임아웃을 명시하는 이유</b>: jakarta.mail 의 연결·읽기·쓰기 타임아웃 기본값은
 * <b>무한</b>이다. 지정하지 않으면 SMTP 서버가 응답을 멈춘 순간 그 소켓은 영원히 열려 있다.
 * 발송을 감싸는 {@code NotificationDispatcher} 의 채널별 상한은 <b>호출자</b>를 3초에 풀어주지만,
 * 뒤에 남은 시도까지 끝내지는 못한다 — 인터럽트는 블로킹 소켓 읽기를 깨우지 못하기 때문이다.
 * 실행자가 가상 스레드라 풀 고갈로 즉시 터지지는 않지만, 죽은 SMTP 대상 하나에 알림을 계속
 * 보내면 회수되지 않는 연결과 스레드가 <b>상한 없이</b> 쌓인다. 겉으로는 알림이 "느려지는" 것으로만
 * 보여 원인에 도달하기 어려운 부류의 고장이다. 그래서 시도를 실제로 끝내는 마감선은 여기에 둔다.
 *
 * <p>기본값은 디스패처의 시도당 예산({@code app.dispatch.per-channel-timeout-ms}, 기본 3초)에
 * 맞춘다. 세 단계가 각각 걸리므로 최악의 경우 그 배수만큼 늦게 끝나지만, 핵심은 값의 크기가 아니라
 * <b>유한하다</b>는 것이다.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger("notification.channel.email");

    private final String username;
    private final String password;
    private final String from;
    private final Session session;

    public EmailChannel(@Value("${app.channels.email.host:smtp.gmail.com}") String host,
                        @Value("${app.channels.email.port:587}") int port,
                        @Value("${app.channels.email.username:}") String username,
                        @Value("${app.channels.email.password:}") String password,
                        @Value("${app.channels.email.from:no-reply@lemuel.co.kr}") String from,
                        @Value("${app.channels.email.timeout-ms:3000}") int timeoutMs) {
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.from = from;
        this.session = createSession(host, port, timeoutMs);
    }

    /**
     * 세션은 발송마다 만들 이유가 없다 — 설정은 기동 시 고정되고 {@code Session} 은 스레드 안전하다.
     * 여기서 한 번 만들어 두면 타임아웃 설정이 빠진 경로가 나중에 생길 여지도 함께 사라진다.
     */
    private Session createSession(String host, int port, int timeoutMs) {
        String timeout = String.valueOf(timeoutMs);
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        // TCP 연결 수립까지.
        props.put("mail.smtp.connectiontimeout", timeout);
        // 서버 응답 대기(소켓 읽기). 응답을 멈춘 서버가 가장 흔한 형태의 고장이다.
        props.put("mail.smtp.timeout", timeout);
        // 본문 쓰기. 수신 측이 읽지 않으면 송신 버퍼가 차서 여기서 멈춘다.
        props.put("mail.smtp.writetimeout", timeout);

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EmailChannel.this.username, EmailChannel.this.password);
            }
        });
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean isEnabled() {
        return !username.isBlank() && !password.isBlank();
    }

    /** 실제로 적용된 SMTP 설정. 타임아웃이 빠지면 조용히 무한 대기가 되므로 테스트가 여기를 본다. */
    Properties smtpProperties() {
        return session.getProperties();
    }

    @Override
    public void send(Notification notification) throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(notification.recipient()));
        message.setSubject(notification.subject());
        message.setText(notification.body());
        Transport.send(message);

        log.debug("email delivered to={}", notification.recipient());
    }
}
