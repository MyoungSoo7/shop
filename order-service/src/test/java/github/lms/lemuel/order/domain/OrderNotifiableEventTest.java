package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "무엇을 고객에게 알릴 것인가" 의 단일 판정표 검증.
 *
 * <p>이 표가 채널 밖에 있어야 하는 이유가 곧 테스트의 이유다 — 메일과 알림톡이 서로 다른 사건에
 * 울리기 시작하면 그건 기능이 아니라 버그로 읽힌다.
 */
class OrderNotifiableEventTest {

    @Test
    @DisplayName("고객이 기다리는 사건들은 모두 통지 대상이다")
    void notifiableTransitions() {
        assertThat(OrderNotifiableEvent.of(OrderStatus.CREATED, OrderStatus.PAID))
                .contains(OrderNotifiableEvent.ORDER_CONFIRMED);
        assertThat(OrderNotifiableEvent.of(OrderStatus.SHIPPING_PENDING, OrderStatus.IN_TRANSIT))
                .contains(OrderNotifiableEvent.SHIPPING_STARTED);
        assertThat(OrderNotifiableEvent.of(OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED))
                .contains(OrderNotifiableEvent.DELIVERED);
        assertThat(OrderNotifiableEvent.of(OrderStatus.PAID, OrderStatus.CANCELLATION_REQUESTED))
                .contains(OrderNotifiableEvent.CANCELLATION_RECEIVED);
        assertThat(OrderNotifiableEvent.of(OrderStatus.PAID, OrderStatus.REFUND_REQUESTED))
                .contains(OrderNotifiableEvent.REFUND_RECEIVED);
        assertThat(OrderNotifiableEvent.of(OrderStatus.CREATED, OrderStatus.CANCELED))
                .contains(OrderNotifiableEvent.ORDER_CANCELED);
        assertThat(OrderNotifiableEvent.of(OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED))
                .contains(OrderNotifiableEvent.REFUND_COMPLETED);
    }

    /**
     * 신청 접수를 알리고 곧바로 "승인됨"까지 알리면 한 사건에 두 번 울린다. 승인은 운영자 흐름을
     * 이력에 남기기 위한 중간 상태일 뿐 고객에게는 의미가 없다.
     */
    @Test
    @DisplayName("승인 중간 상태와 출고 준비는 통지 대상이 아니다")
    void internalTransitionsAreSilent() {
        assertThat(OrderNotifiableEvent.of(
                OrderStatus.CANCELLATION_REQUESTED, OrderStatus.CANCELLATION_APPROVED)).isEmpty();
        assertThat(OrderNotifiableEvent.of(OrderStatus.PAID, OrderStatus.SHIPPING_PENDING)).isEmpty();
    }

    /**
     * 환불 경로는 결제 컨텍스트가 주문을 REFUNDED 로 올리고 승인 서비스가 같은 상태로 한 번 더
     * 확정하는 식으로 겹쳐 돈다(도메인 멱등이 이를 허용한다). 현재 상태만 보고 판정하면 그 겹침이
     * 그대로 중복 발송이 된다.
     */
    @Test
    @DisplayName("제자리 전이는 통지하지 않는다 — 멱등 재확정이 두 번 울리지 않게")
    void sameStatusIsNotAnEvent() {
        assertThat(OrderNotifiableEvent.of(OrderStatus.REFUNDED, OrderStatus.REFUNDED)).isEmpty();
        assertThat(OrderNotifiableEvent.of(OrderStatus.DELIVERED, OrderStatus.DELIVERED)).isEmpty();
    }

    @Test
    @DisplayName("모든 사건에 고객이 읽을 한 줄 요약이 있다")
    void everyEventHasSummary() {
        for (OrderNotifiableEvent event : OrderNotifiableEvent.values()) {
            assertThat(event.summary()).isNotBlank();
            assertThat(event.summary()).isNotEqualTo(event.name());   // 영문 enum 이 새지 않는다
        }
    }
}
