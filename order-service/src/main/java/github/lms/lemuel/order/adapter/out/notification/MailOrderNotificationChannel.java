package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 이메일 주문 알림 채널 — {@link OrderNotificationChannel} 의 메일 구현.
 *
 * <p>전송 실패 시 예외를 던지며, 채널 간 격리(주문 트랜잭션 보호)는
 * {@link CompositeOrderNotificationAdapter} 가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailOrderNotificationChannel implements OrderNotificationChannel {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@lemuel.com}")
    private String fromEmail;

    @Override
    public String channelName() {
        return "mail";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void sendOrderConfirmation(String email, Order order) {
        log.info("주문 확인 메일 발송 시도: to={}, orderId={}", email, order.getId());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("[Lemuel] 주문이 완료되었습니다.");
        message.setText(String.format(
                "안녕하세요.\n\nLemuel에서 주문하신 내역을 안내드립니다.\n\n" +
                "주문 번호: %d\n" +
                "상품 ID: %d\n" +
                "결제 금액: %s\n" +
                "주문 일시: %s\n\n" +
                "이용해 주셔서 감사합니다.",
                order.getId(),
                order.getProductId(),
                order.getAmount().toString(),
                order.getCreatedAt().toString()
        ));

        mailSender.send(message);
        log.info("주문 확인 메일 발송 완료: to={}, orderId={}", email, order.getId());
    }
}
