package github.lms.lemuel.order.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 주문 상태 Enum + 허용 전이(상태머신) 정의.
 *
 * <p>전이 규칙을 도메인에 명시해 임의 전이(예: CREATED → DELIVERED, REFUNDED → PAID)를 차단한다.
 * 실제 전이는 {@link Order#transitionTo(OrderStatus)} 가 이 규칙으로 검증한다.
 *
 * <p>환불(REFUNDED)은 결제 이후 어떤 진행 단계(배송 포함)에서도 발생할 수 있어 종단 도달을 관대하게 허용하되,
 * 결제 전(CREATED)이나 종단 상태(CANCELED/REFUNDED)에서의 비정상 전이는 막는다.
 *
 * <p>환불 완료 종단은 {@link #REFUNDED} 하나로 일원화한다 — 관리자 환불 승인(approveRefund)이든
 * 직접 환불(/payments/{id}/refund)이든, 실제 PG 환불 성공 시 payment 가 주문을 REFUNDED 로 전이한다.
 */
public enum OrderStatus {
    CREATED,    // 주문 생성됨(결제 전)
    PAID,       // 결제 완료로 주문 확정
    SHIPPING_PENDING,
    IN_TRANSIT,
    DELIVERED,
    CANCELLATION_REQUESTED,
    CANCELLATION_APPROVED,
    REFUND_REQUESTED,
    /**
     * 교환 신청됨 — 고객이 <b>환불이 아니라 같은 상품으로의 교체</b>를 요청한 상태.
     *
     * <p>환불 신청과 나뉘어 있는 이유는 끝나는 곳이 다르기 때문이다. 환불 신청에서 갈 수 있는 곳은
     * {@link #REFUNDED} 뿐이지만, 교환은 회수 → 재배송을 거쳐 <b>배송 흐름으로 되돌아간다</b>
     * ({@code SHIPPING_PENDING}). 교환을 환불 신청으로 받아 두면 그 주문은 되돌아갈 길이 없어
     * 운영자가 손으로 상태를 되돌리기 전까지 묶인다.
     *
     * <p>교환 도중 재고가 없어 환불로 전환하는 경로({@code → REFUND_REQUESTED})도 함께 연다.
     */
    EXCHANGE_REQUESTED,
    /**
     * @deprecated 환불 완료 종단은 {@link #REFUNDED} 로 일원화됨. 신규 전이 없음.
     * enum 값 자체는 과거 이 상태로 기록된 DB 행과의 호환을 위해 보존한다.
     */
    @Deprecated
    REFUND_COMPLETED,
    CANCELED,   // 결제 전 취소 / 취소 승인 종단
    REFUNDED;   // 결제 후 환불 완료 종단 (단일 환불 완료 종단)

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(PAID, CANCELED, CANCELLATION_REQUESTED));
        ALLOWED.put(PAID, EnumSet.of(SHIPPING_PENDING, REFUND_REQUESTED, REFUNDED, CANCELLATION_REQUESTED,
                EXCHANGE_REQUESTED));
        ALLOWED.put(SHIPPING_PENDING, EnumSet.of(IN_TRANSIT, REFUND_REQUESTED, REFUNDED, EXCHANGE_REQUESTED));
        ALLOWED.put(IN_TRANSIT, EnumSet.of(DELIVERED, REFUND_REQUESTED, REFUNDED, EXCHANGE_REQUESTED));
        ALLOWED.put(DELIVERED, EnumSet.of(REFUND_REQUESTED, REFUNDED, EXCHANGE_REQUESTED));
        ALLOWED.put(CANCELLATION_REQUESTED, EnumSet.of(CANCELLATION_APPROVED, CANCELED));
        ALLOWED.put(CANCELLATION_APPROVED, EnumSet.of(CANCELED, REFUND_REQUESTED, REFUNDED));
        ALLOWED.put(REFUND_REQUESTED, EnumSet.of(REFUNDED));
        // 교환은 재배송으로 배송 흐름에 되돌아가고(SHIPPING_PENDING), 교체할 재고가 없으면 환불로
        // 전환된다(REFUND_REQUESTED). REFUNDED 직행은 이미 전액 환불된 결제의 멱등 확정 경로다.
        ALLOWED.put(EXCHANGE_REQUESTED, EnumSet.of(SHIPPING_PENDING, REFUND_REQUESTED, REFUNDED));
        // 종단 상태 — 추가 전이 없음
        ALLOWED.put(REFUND_COMPLETED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(OrderStatus.class));
    }

    /** 현재 상태에서 {@code target} 으로 전이 가능한지. */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** 더 이상 전이가 없는 종단 상태인지. */
    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Collections.emptySet()).isEmpty();
    }

    /**
     * 문자열을 주문 상태로 옮긴다. 모르는 값이면 던진다.
     *
     * <p>예전 기본값은 {@link #CREATED} 였다. 종단 상태(환불·취소 완료)로 끝난 주문이
     * 읽기에 실패하는 순간 <b>방금 만든 주문</b>이 되고, {@link #canTransitionTo} 는 거기서부터
     * 취소·결제를 다시 허용한다. 상태머신을 아무리 촘촘히 짜도 입구에서 상태를 지어내면
     * 소용이 없다.
     */
    public static OrderStatus fromString(String status) {
        OrderStatus parsed = fromStringOrNull(status);
        if (parsed == null) {
            throw new UnknownEnumValueException(OrderStatus.class, status);
        }
        return parsed;
    }

    /** 모르는 값·빈 값이면 {@code null}. 조회 필터처럼 던지지 않는 쪽이 옳은 자리에서만 쓴다. */
    public static OrderStatus fromStringOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
