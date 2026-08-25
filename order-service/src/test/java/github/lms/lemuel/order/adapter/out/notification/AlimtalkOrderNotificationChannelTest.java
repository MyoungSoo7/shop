package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림톡 채널 단위 테스트 — <b>어떤 사건에 어떤 번호로 어떤 템플릿이 나가는가</b>.
 *
 * <p>실제 발신은 하지 않는다. 대행사 계약·템플릿 승인 전이므로 벤더 SDK 대신
 * {@link AlimtalkSender} 가짜 구현으로 발송 규칙만 고정한다 — 이 인터페이스가 존재하는 이유가
 * 바로 이 테스트다.
 */
class AlimtalkOrderNotificationChannelTest {

    /** 발신 대신 기록만 하는 가짜 발신기. */
    private record Sent(String phone, String templateCode, String message) { }

    private final List<Sent> sent = new ArrayList<>();
    private final AlimtalkSender recorder = (phone, templateCode, message) ->
            sent.add(new Sent(phone, templateCode, message));

    private static AlimtalkProperties props(Map<OrderNotifiableEvent, String> templates) {
        AlimtalkProperties p = new AlimtalkProperties();
        p.setEnabled(true);
        p.setTemplates(templates);
        return p;
    }

    private static Order orderWithPhone(String phone) {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.assignId(7L);
        order.attachShippingAddress(new ShippingAddressSnapshot(
                "홍길동", phone, "06236", "서울시 강남구", null, null));
        return order;
    }

    @Test
    @DisplayName("승인된 템플릿이 있는 사건은 배송지 번호로 나간다")
    void sendsWithApprovedTemplate() {
        var channel = new AlimtalkOrderNotificationChannel(recorder,
                props(Map.of(OrderNotifiableEvent.SHIPPING_STARTED, "TPL_SHIP_001")));

        channel.sendStatusChanged("buyer@test.com", orderWithPhone("010-1234-5678"),
                OrderNotifiableEvent.SHIPPING_STARTED);

        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.phone()).isEqualTo("010-1234-5678");
            assertThat(s.templateCode()).isEqualTo("TPL_SHIP_001");
            assertThat(s.message()).contains("상품이 발송되었습니다").contains("7");
        });
    }

    /**
     * 알림톡은 사전 승인된 템플릿으로만 나간다. 코드가 보내고 싶어도 승인된 코드가 없으면 대행사가
     * 거절하므로, 승인이 끝난 사건부터 설정으로 하나씩 켜는 것이 실제 운영 순서다.
     */
    @Test
    @DisplayName("승인 템플릿이 없는 사건은 시도조차 하지 않는다")
    void skipsUnapprovedEvent() {
        var channel = new AlimtalkOrderNotificationChannel(recorder,
                props(Map.of(OrderNotifiableEvent.SHIPPING_STARTED, "TPL_SHIP_001")));

        channel.sendStatusChanged("buyer@test.com", orderWithPhone("010-1234-5678"),
                OrderNotifiableEvent.REFUND_RECEIVED);

        assertThat(sent).isEmpty();
    }

    /**
     * 스냅샷 도입 전 레거시 주문과 단건 주문 경로에는 배송지가 없다. 알림 하나 못 보낸 것이 주문
     * 처리를 막을 이유는 없으므로 예외 없이 건너뛴다.
     */
    @Test
    @DisplayName("배송지 스냅샷이 없는 주문은 조용히 건너뛴다")
    void skipsOrderWithoutAddress() {
        Order legacy = Order.create(1L, 1L, new BigDecimal("10000"));
        legacy.assignId(7L);
        var channel = new AlimtalkOrderNotificationChannel(recorder,
                props(Map.of(OrderNotifiableEvent.SHIPPING_STARTED, "TPL_SHIP_001")));

        channel.sendStatusChanged("buyer@test.com", legacy, OrderNotifiableEvent.SHIPPING_STARTED);

        assertThat(sent).isEmpty();
    }

    /**
     * 수신 번호가 회원 정보가 아니라 <b>주문서에 굳은 배송지 번호</b>라는 것이 이 채널의 핵심이다.
     * 회원 번호를 쓰면 선물 주문의 통지가 받는 사람이 아니라 결제자에게 간다.
     */
    @Test
    @DisplayName("수신 번호는 이메일이 아니라 주문 시점 배송지에서 나온다")
    void phoneComesFromOrderSnapshot() {
        var channel = new AlimtalkOrderNotificationChannel(recorder,
                props(Map.of(OrderNotifiableEvent.REFUND_RECEIVED, "TPL_RFND_001")));

        channel.sendStatusChanged(null, orderWithPhone("010-9999-0000"),
                OrderNotifiableEvent.REFUND_RECEIVED);

        assertThat(sent).singleElement()
                .extracting(Sent::phone).isEqualTo("010-9999-0000");
    }

    /** 접수 확인은 메일이 이미 보낸다 — 겹쳐 보내면 고객이 같은 사건으로 두 번 알림을 받는다. */
    @Test
    @DisplayName("주문 접수 확인은 이 채널이 보내지 않는다")
    void doesNotDuplicateOrderConfirmation() {
        var channel = new AlimtalkOrderNotificationChannel(recorder, props(Map.of()));

        channel.sendOrderConfirmation("buyer@test.com", orderWithPhone("010-1234-5678"));

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("설정이 꺼져 있으면 디스패처가 건너뛴다")
    void disabledByProperties() {
        AlimtalkProperties off = new AlimtalkProperties();

        assertThat(off.isEnabled()).isFalse();   // 기본 꺼짐 — 계약 전에 켜지지 않는다
        assertThat(new AlimtalkOrderNotificationChannel(recorder, off).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("채널 이름은 로그에서 식별 가능하다")
    void channelName() {
        assertThat(new AlimtalkOrderNotificationChannel(recorder, props(Map.of())).channelName())
                .isEqualTo("alimtalk");
    }
}
