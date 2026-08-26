package github.lms.lemuel.order.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.Locale;

/**
 * 반품·교환·취소 신청의 종류.
 *
 * <p>세 가지를 한 축에 둔 이유는 고객이 내는 신청이 실제로 하나의 사건이기 때문이다 — 무엇을
 * 요구하느냐만 다르고, 사유·환불 계좌·회수 송장은 공통이다. 다른 것은 <b>끝나는 곳</b>이다:
 * 취소·반품은 환불로 끝나고, 교환은 재배송으로 배송 흐름에 되돌아간다.
 */
public enum ReturnRequestType {

    /** 출고 전 주문 취소 — 물건이 고객에게 가지 않았으므로 회수가 없다. */
    CANCEL(OrderStatus.CANCELLATION_REQUESTED, false),

    /** 반품 — 물건을 회수하고 환불한다. */
    RETURN(OrderStatus.REFUND_REQUESTED, true),

    /** 교환 — 물건을 회수하고 같은 상품을 다시 보낸다. */
    EXCHANGE(OrderStatus.EXCHANGE_REQUESTED, true);

    private final OrderStatus requestedOrderStatus;
    private final boolean collectsGoods;

    ReturnRequestType(OrderStatus requestedOrderStatus, boolean collectsGoods) {
        this.requestedOrderStatus = requestedOrderStatus;
        this.collectsGoods = collectsGoods;
    }

    /** 이 신청이 주문을 옮겨 놓는 상태. */
    public OrderStatus requestedOrderStatus() {
        return requestedOrderStatus;
    }

    /** 고객에게서 물건을 돌려받는 종류인지 — 회수 송장을 요구할 근거. */
    public boolean collectsGoods() {
        return collectsGoods;
    }

    /** 돈을 되돌려주는 종류인지 — 환불 수취 계좌를 요구할 근거(교환은 요구하지 않는다). */
    public boolean refundsMoney() {
        return this != EXCHANGE;
    }

    public static ReturnRequestType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new UnknownEnumValueException(ReturnRequestType.class, value);
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownEnumValueException(ReturnRequestType.class, value);
        }
    }
}
