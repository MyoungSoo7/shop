package github.lms.lemuel.shipping.domain;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.util.Objects;

/**
 * 배송지 값 객체. 한국 주소 체계 기준.
 */
public record ShippingAddress(
        String recipientName,
        String phone,
        String postalCode,
        String address1,
        String address2,
        String deliveryMemo
) {
    public ShippingAddress {
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(postalCode, "postalCode");
        Objects.requireNonNull(address1, "address1");
        if (recipientName.isBlank()) throw new ShipmentInvariantViolationException("수령인 이름 필수");
        if (phone.isBlank()) throw new ShipmentInvariantViolationException("전화번호 필수");
        if (postalCode.isBlank()) throw new ShipmentInvariantViolationException("우편번호 필수");
        if (address1.isBlank()) throw new ShipmentInvariantViolationException("주소 필수");
    }
}
