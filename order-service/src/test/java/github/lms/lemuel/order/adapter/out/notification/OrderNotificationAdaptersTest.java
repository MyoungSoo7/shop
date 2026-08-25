package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import github.lms.lemuel.order.domain.OrderStatus;
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
import static org.mockito.ArgumentMatchers.eq;
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

    // ── 생애주기 통지(sendStatusChanged) ───────────────────────────
    // 팬아웃과 실패 격리는 주문 확인과 같은 성질이므로 여기서는 사건 판정이 디스패처에 한 번만 있다는
    // 점을 본다. 채널마다 전이표를 다시 읽으면 "메일은 울렸는데 알림톡은 안 울린다" 가 된다.

    @Test
    @DisplayName("Composite: 상태 변경도 활성 채널에만 팬아웃하고 사건은 여기서 판정한다")
    void composite_statusChanged_fanOut() {
        OrderNotificationChannel enabled = mock(OrderNotificationChannel.class);
        OrderNotificationChannel disabled = mock(OrderNotificationChannel.class);
        when(enabled.isEnabled()).thenReturn(true);
        when(disabled.isEnabled()).thenReturn(false);

        CompositeOrderNotificationAdapter adapter =
                new CompositeOrderNotificationAdapter(List.of(enabled, disabled));

        adapter.sendStatusChanged("buyer@b.com", orderInStatus(OrderStatus.IN_TRANSIT),
                OrderStatus.SHIPPING_PENDING);

        verify(enabled).sendStatusChanged(eq("buyer@b.com"), any(),
                eq(OrderNotifiableEvent.SHIPPING_STARTED));
        verify(disabled, never()).sendStatusChanged(any(), any(), any());
    }

    @Test
    @DisplayName("Composite: 알릴 사건이 아닌 전이는 채널을 부르지 않는다")
    void composite_statusChanged_skipsSilentTransition() {
        OrderNotificationChannel enabled = mock(OrderNotificationChannel.class);

        CompositeOrderNotificationAdapter adapter =
                new CompositeOrderNotificationAdapter(List.of(enabled));

        // 출고 준비는 창고 내부 사정 — 고객에게 알릴 사건이 아니다.
        adapter.sendStatusChanged("buyer@b.com", orderInStatus(OrderStatus.SHIPPING_PENDING),
                OrderStatus.PAID);

        verify(enabled, never()).sendStatusChanged(any(), any(), any());
        verify(enabled, never()).isEnabled();   // 판정이 팬아웃보다 앞이다
    }

    @Test
    @DisplayName("Composite: 상태 변경도 한 채널 실패가 다른 채널로 번지지 않는다")
    void composite_statusChanged_failureIsolation() {
        OrderNotificationChannel failing = mock(OrderNotificationChannel.class);
        OrderNotificationChannel healthy = mock(OrderNotificationChannel.class);
        when(failing.isEnabled()).thenReturn(true);
        when(healthy.isEnabled()).thenReturn(true);
        when(failing.channelName()).thenReturn("failing");
        doThrow(new RuntimeException("boom"))
                .when(failing).sendStatusChanged(any(), any(), any());

        CompositeOrderNotificationAdapter adapter =
                new CompositeOrderNotificationAdapter(List.of(failing, healthy));

        adapter.sendStatusChanged("buyer@b.com", orderInStatus(OrderStatus.REFUND_REQUESTED),
                OrderStatus.PAID);

        verify(healthy).sendStatusChanged(any(), any(), eq(OrderNotifiableEvent.REFUND_RECEIVED));
    }

    @Test
    @DisplayName("Mail 채널: 수신 주소를 모르면 상태 변경 메일을 보내지 않는다")
    void mailChannel_statusChanged_skipsUnknownAddress() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MailOrderNotificationChannel channel = new MailOrderNotificationChannel(mailSender);

        channel.sendStatusChanged(null, order(), OrderNotifiableEvent.SHIPPING_STARTED);

        // 여기서 예외를 던지면 애초에 보낼 수 없던 건이 디스패처 로그에 "전송 실패" 로 남는다.
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Mail 채널: 상태 변경 메일은 한글 요약을 제목에 싣는다")
    void mailChannel_statusChanged_sends() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MailOrderNotificationChannel channel = new MailOrderNotificationChannel(mailSender);
        ReflectionTestUtils.setField(channel, "fromEmail", "noreply@lemuel.com");

        channel.sendStatusChanged("buyer@b.com", order(), OrderNotifiableEvent.REFUND_RECEIVED);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("환불 신청이 접수되었습니다");
        assertThat(captor.getValue().getTo()).containsExactly("buyer@b.com");
    }

    /** 지정 상태의 주문 — 전이 규칙을 우회해 상태만 세운다. */
    private static Order orderInStatus(OrderStatus status) {
        return Order.rehydrate(7L, 1L, 1L, new BigDecimal("10000"), status,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                BigDecimal.ZERO, true);
    }
}
