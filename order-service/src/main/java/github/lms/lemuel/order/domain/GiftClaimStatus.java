package github.lms.lemuel.order.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 선물 수령(gift claim)의 진행 상태.
 *
 * <p>주문 상태({@link OrderStatus})와 <b>별개의 축</b>이다. 주문은 "결제·배송이 어디까지 왔는가"를,
 * 이쪽은 "받는 사람이 주소를 냈는가"를 말한다. 둘을 한 축에 섞으면 결제는 끝났는데 주소가 없는
 * 정상적인 중간 상태를 표현할 자리가 없어진다 — 그게 선물 주문의 본질적인 상태다.
 */
public enum GiftClaimStatus {

    /** 링크는 나갔고 받는 사람이 아직 본인확인을 하지 않았다. */
    PENDING,

    /** 받는 사람이 휴대폰 인증을 통과했다. 아직 주소는 없다. */
    VERIFIED,

    /** 받는 사람이 배송지를 냈다 — 여기서 주문에 배송지가 붙고 배송이 시작된다. */
    CLAIMED,

    /** 유효기간이 지났다. 되살리지 않는다 — 보낸 사람이 다시 보내야 한다. */
    EXPIRED,

    /** 보낸 사람이 거둬들였거나 주문이 취소됐다. */
    CANCELED;

    private static final Set<GiftClaimStatus> TERMINAL =
            EnumSet.of(CLAIMED, EXPIRED, CANCELED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 아직 받는 사람이 무언가 할 수 있는 상태인지. */
    public boolean isOpen() {
        return !isTerminal();
    }

    /**
     * 저장된 문자열을 상수로 되돌린다. 모르는 값이면 예외 — {@code valueOf} 의 날것 예외 대신
     * 어느 enum 의 어떤 값이었는지가 메시지에 남는다.
     */
    public static GiftClaimStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new UnknownEnumValueException(GiftClaimStatus.class, value);
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownEnumValueException(GiftClaimStatus.class, value);
        }
    }

    public boolean canTransitionTo(GiftClaimStatus target) {
        if (target == null || this == target) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == VERIFIED || target == EXPIRED || target == CANCELED;
            // CLAIMED 는 VERIFIED 에서만 온다. PENDING → CLAIMED 를 열면 인증을 건너뛰고
            // 주소를 넣는 경로가 생기고, 그 순간 링크를 주운 사람이 배송지를 바꿀 수 있다.
            case VERIFIED -> target == CLAIMED || target == EXPIRED || target == CANCELED;
            case CLAIMED, EXPIRED, CANCELED -> false;
        };
    }
}
