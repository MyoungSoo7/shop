package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.util.Objects;

/**
 * 주문 시점 배송지 스냅샷 — "이 주문을 낼 때 고객이 어디로 보내달라고 했는가".
 *
 * <p><b>왜 배송(shipment)의 주소와 별개로 주문이 들고 있나.</b> 배송지는 출고 전까지 바뀔 수 있고
 * ({@code PATCH /orders/{id}/shipment/address}), 바뀌면 {@code shipments} 의 주소는 <b>덮어써진다</b>.
 * 그 자리만 진실이면 "고객이 원래 어디로 요청했는가"가 남지 않아, 오배송 분쟁과 배송지 변조를
 * 사후에 판정할 근거가 사라진다. 주문서는 영수증과 같은 성질의 기록이라 바뀌지 않아야 한다 —
 * {@link OrderItem} 이 상품명·단가를 주문 시점으로 굳혀 두는 것과 같은 이유다.
 *
 * <p>배송 컨텍스트의 {@code shipping.domain.ShippingAddress} 와 필드가 같지만 <b>같은 타입을 쓰지
 * 않는다.</b> 스냅샷은 정의상 그 시점에 복사된 값이고, 배송 쪽 VO 는 살아 움직이는 배송 대상이다.
 * 한 타입으로 묶으면 배송 규칙이 바뀔 때 과거 주문서의 해석까지 따라 바뀐다.
 */
public record ShippingAddressSnapshot(
        String recipientName,
        String phone,
        String postalCode,
        String address1,
        String address2,
        String deliveryMemo
) {
    public ShippingAddressSnapshot {
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(postalCode, "postalCode");
        Objects.requireNonNull(address1, "address1");
        if (recipientName.isBlank()) throw new OrderInvariantViolationException("수령인 이름 필수");
        if (phone.isBlank()) throw new OrderInvariantViolationException("전화번호 필수");
        if (postalCode.isBlank()) throw new OrderInvariantViolationException("우편번호 필수");
        if (address1.isBlank()) throw new OrderInvariantViolationException("주소 필수");
        address2 = blankToNull(address2);
        deliveryMemo = blankToNull(deliveryMemo);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
