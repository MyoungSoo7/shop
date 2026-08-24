package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주문 알림 채널/디스패처 단위 테스트 — 메일 채널, Slack 채널의 활성 판별, Composite 팬아웃/실패 격리.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotificationAdaptersTest {

    private Order order() {
        Order o = Order.create(1L, 1L, new BigDecimal("10000"));
        o.assignId(7L);
        return o;
    }

    // ── MailOrderNotificationChannel ──────────────────────────────

    @Test
    @DisplayName("Mail 채널: 이름/활성 + 주문 확인 메일 전송")
    void mailChannel_sends() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MailOrderNotificationChannel channel = new MailOrderNotificationChannel(mailSender);
        ReflectionTestUtils.setField(channel, "fromEmail", "noreply@lemuel.com");

        assertThat(channel.channelName()).isEqualTo("mail");
        assertThat(channel.isEnabled()).isTrue();

        channel.sendOrderConfirmation("buyer@b.com", order());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("buyer@b.com");
        assertThat(sent.getFrom()).isEqualTo("noreply@lemuel.com");
        assertThat(sent.getText()).contains("주문 번호: 7");
    }

    // ── SlackOrderNotificationChannel ─────────────────────────────

    @Test
    @DisplayName("Slack 채널: webhook 이 비어있으면 비활성")
    void slackChannel_disabledWhenBlank() {
        SlackOrderNotificationChannel channel = new SlackOrderNotificationChannel("");
        assertThat(channel.channelName()).isEqualTo("slack");
        assertThat(channel.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Slack 채널: webhook 이 지정되면 활성")
    void slackChannel_enabledWhenSet() {
        SlackOrderNotificationChannel channel =
                new SlackOrderNotificationChannel("https://hooks.slack.com/services/x");
        assertThat(channel.isEnabled()).isTrue();
    }

    // ── CompositeOrderNotificationAdapter ─────────────────────────

    @Test
    @DisplayName("Composite: 활성 채널에만 팬아웃, 비활성 채널은 건너뛴다")
    void composite_fanOut() {
        OrderNotificationChannel enabled = mock(OrderNotificationChannel.class);
        OrderNotificationChannel disabled = mock(OrderNotificationChannel.class);
        when(enabled.isEnabled()).thenReturn(true);
        when(disabled.isEnabled()).thenReturn(false);

        CompositeOrderNotificationAdapter adapter =
                new CompositeOrderNotificationAdapter(List.of(enabled, disabled));

        adapter.sendOrderConfirmation("buyer@b.com", order());

        verify(enabled).sendOrderConfirmation(any(), any());
        verify(disabled, never()).sendOrderConfirmation(any(), any());
    }

    @Test
    @DisplayName("Composite: 한 채널이 실패해도 다른 채널은 계속 전송(실패 격리)")
    void composite_failureIsolation() {
        OrderNotificationChannel failing = mock(OrderNotificationChannel.class);
        OrderNotificationChannel healthy = mock(OrderNotificationChannel.class);
        when(failing.isEnabled()).thenReturn(true);
        when(healthy.isEnabled()).thenReturn(true);
        when(failing.channelName()).thenReturn("failing");
        doThrow(new RuntimeException("boom"))
                .when(failing).sendOrderConfirmation(any(), any());

        CompositeOrderNotificationAdapter adapter =
                new CompositeOrderNotificationAdapter(List.of(failing, healthy));

        adapter.sendOrderConfirmation("buyer@b.com", order()); // 예외를 삼켜야 한다

        verify(healthy).sendOrderConfirmation(any(), any());
    }
}
