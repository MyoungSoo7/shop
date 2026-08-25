package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.ShippingAddressSnapshot;

/**
 * 주문 생성과 <b>같은 트랜잭션</b>에서 배송(PENDING)을 만드는 아웃바운드 포트.
 *
 * <p>주문 쪽에 포트를 두는 이유: 주문 서비스가 배송 컨텍스트의 값 타입을 알 필요가 없기 때문이다.
 * 변환은 어댑터가 맡고, 주문은 "배송을 시작해 달라"는 요청만 자기 언어(스냅샷)로 보낸다.
 */
@FunctionalInterface
public interface CreateShipmentPort {

    /** 이미 배송이 있는 주문이면 배송 컨텍스트가 거부한다(중복 생성 방지는 그쪽 규칙). */
    void createForOrder(Long orderId, ShippingAddressSnapshot address);
}
