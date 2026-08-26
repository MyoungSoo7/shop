package github.lms.lemuel.order.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 반품·교환 신청 자체의 진행 상태.
 *
 * <p>주문 상태({@link OrderStatus})와 별개인 이유는 <b>보는 사람이 다르기</b> 때문이다. 주문
 * 상태는 "이 주문이 지금 어디까지 왔는가"이고, 여기는 "그 신청을 운영자가 어디까지 처리했는가"다.
 * 주문 하나가 반품 거절 뒤 교환으로 다시 신청되는 흐름이 정상이라, 신청은 여러 건이 남고 주문
 * 상태는 하나뿐이다.
 *
 * <p>{@link #COLLECTED} 는 회수 송장이 도착을 확인해 준 시점이다. 이 칸이 없으면 "물건을 받았는가"가
 * 어디에도 남지 않아 환불 시점이 전화 통화에 의존한다.
 */
public enum ReturnRequestStatus {

    REQUESTED,
    APPROVED,
    /** 회수 완료 — 물건이 판매자에게 돌아왔다. 반품은 여기서 환불로, 교환은 재배송으로 간다. */
    COLLECTED,
    COMPLETED,
    REJECTED,
    /** 고객이 스스로 거둬들인 신청. */
    WITHDRAWN;

    private static final Map<ReturnRequestStatus, Set<ReturnRequestStatus>> ALLOWED =
            new EnumMap<>(ReturnRequestStatus.class);

    static {
        ALLOWED.put(REQUESTED, EnumSet.of(APPROVED, REJECTED, WITHDRAWN));
        // 승인 후에도 고객이 마음을 바꿀 수 있다. 다만 물건이 이미 돌아온 뒤(COLLECTED)에는
        // 철회가 불가능하다 — 그 물건을 되돌려 보내는 일은 새 배송이지 철회가 아니다.
        ALLOWED.put(APPROVED, EnumSet.of(COLLECTED, COMPLETED, REJECTED, WITHDRAWN));
        ALLOWED.put(COLLECTED, EnumSet.of(COMPLETED, REJECTED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(ReturnRequestStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ReturnRequestStatus.class));
        ALLOWED.put(WITHDRAWN, EnumSet.noneOf(ReturnRequestStatus.class));
    }

    public boolean canTransitionTo(ReturnRequestStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** 아직 운영자가 손대야 할 신청인지 — 주문당 하나만 열려 있을 수 있다(부분 유니크 인덱스). */
    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED || this == COLLECTED;
    }

    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Collections.emptySet()).isEmpty();
    }

    public static ReturnRequestStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new UnknownEnumValueException(ReturnRequestStatus.class, value);
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownEnumValueException(ReturnRequestStatus.class, value);
        }
    }
}
