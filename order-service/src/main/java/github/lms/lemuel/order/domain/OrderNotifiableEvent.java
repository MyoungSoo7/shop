package github.lms.lemuel.order.domain;

import java.util.Optional;

/**
 * 고객에게 <b>알릴 만한</b> 주문 사건 — 상태 전이의 부분집합.
 *
 * <p>모든 전이가 통지 대상은 아니다. {@code CANCELLATION_APPROVED} 처럼 운영자 승인 흐름을
 * 남기기 위한 중간 상태는 고객 입장에서 아무 의미가 없고, 곧바로 종단({@code CANCELED}·
 * {@code REFUNDED})이 따라오므로 알리면 한 사건에 두 번 울린다.
 *
 * <p>무엇을 알릴지를 <b>채널이 아니라 여기서</b> 정하는 이유는, 채널이 늘 때마다 같은 판단을
 * 다시 쓰지 않기 위해서다. 메일과 알림톡이 서로 다른 사건에 울리기 시작하면 그건 기능이 아니라
 * 버그로 읽힌다.
 */
public enum OrderNotifiableEvent {

    /** 주문 접수 — 결제 완료. */
    ORDER_CONFIRMED("주문이 접수되었습니다"),

    /** 배송 시작 — 고객이 가장 기다리는 통지다. */
    SHIPPING_STARTED("상품이 발송되었습니다"),

    /** 배송 완료. */
    DELIVERED("상품이 배송 완료되었습니다"),

    /** 취소 신청 접수 — "접수됐다"는 확인이 없으면 고객은 신청이 먹혔는지 알 수 없다. */
    CANCELLATION_RECEIVED("취소 신청이 접수되었습니다"),

    /** 환불 신청 접수. */
    REFUND_RECEIVED("환불 신청이 접수되었습니다"),

    /**
     * 교환 신청 접수.
     *
     * <p>환불 신청과 문구를 나눈다. 교환은 돈이 돌아오지 않고 물건이 다시 오는 일이라, "환불 신청이
     * 접수되었습니다"를 받은 고객은 오지 않을 입금을 기다리게 된다.
     */
    EXCHANGE_RECEIVED("교환 신청이 접수되었습니다"),

    /** 주문 취소 확정. */
    ORDER_CANCELED("주문이 취소되었습니다"),

    /** 환불 완료 — 돈이 실제로 돌아간 시점. */
    REFUND_COMPLETED("환불이 완료되었습니다");

    private final String summary;

    OrderNotifiableEvent(String summary) {
        this.summary = summary;
    }

    /** 채널이 공통으로 쓰는 한 줄 요약(제목·본문 첫 줄). */
    public String summary() {
        return summary;
    }

    /**
     * 상태 전이를 알릴 사건으로 옮긴다. 알릴 것이 없으면 빈 값.
     *
     * <p>{@code previous} 를 받는 이유는 <b>제자리 전이를 걸러내기 위해서</b>다. 이 도메인의
     * 환불 경로는 결제 컨텍스트가 먼저 주문을 {@code REFUNDED} 로 올리고 승인 서비스가 같은
     * 상태로 한 번 더 확정하는 식으로 겹쳐 도는데(도메인 멱등이 이를 허용한다), 현재 상태만 보고
     * 알리면 그 겹침이 그대로 중복 발송이 된다.
     */
    public static Optional<OrderNotifiableEvent> of(OrderStatus previous, OrderStatus current) {
        if (current == null || current == previous) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (current) {
            case PAID -> ORDER_CONFIRMED;
            case IN_TRANSIT -> SHIPPING_STARTED;
            case DELIVERED -> DELIVERED;
            case CANCELLATION_REQUESTED -> CANCELLATION_RECEIVED;
            case REFUND_REQUESTED -> REFUND_RECEIVED;
            case EXCHANGE_REQUESTED -> EXCHANGE_RECEIVED;
            case CANCELED -> ORDER_CANCELED;
            case REFUNDED, REFUND_COMPLETED -> REFUND_COMPLETED;
            // CREATED(아직 결제 전)·SHIPPING_PENDING(창고 내부 사정)·CANCELLATION_APPROVED(중간 상태)는
            // 고객에게 알릴 사건이 아니다.
            case CREATED, SHIPPING_PENDING, CANCELLATION_APPROVED -> null;
        });
    }
}
