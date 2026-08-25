package github.lms.lemuel.shipping.application.port.out;

/**
 * 배송 리소스의 소유자 판정을 위해 <b>주문 소유자</b>만 얻어오는 아웃바운드 포트.
 *
 * <p>배송은 자기 자신에 소유자를 갖고 있지 않다 — {@code Shipment} 가 아는 것은 {@code orderId} 뿐이다.
 * 그래서 "이 배송이 내 것인가" 는 반드시 주문을 거쳐야 답할 수 있고, 그 한 가지 사실만 필요하므로
 * 주문 전체가 아니라 소유자 식별자만 노출한다.
 *
 * <p>소유자를 알 수 없으면 {@code null} 을 돌려준다. 그 값을 받은 검증부는 <b>통과가 아니라 거부</b>로
 * 처리해야 한다(fail-closed) — 대조 불가를 통과로 두면 게이트가 조용히 꺼진다.
 */
public interface LoadOrderOwnerPort {

    /** 주문 소유자(users.id). 주문이 없거나 소유자를 모르면 null. */
    Long findOwnerUserId(Long orderId);
}
