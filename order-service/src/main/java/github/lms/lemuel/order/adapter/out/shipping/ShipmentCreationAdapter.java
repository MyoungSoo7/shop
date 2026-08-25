package github.lms.lemuel.order.adapter.out.shipping;

import github.lms.lemuel.order.application.port.out.CreateShipmentPort;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import org.springframework.stereotype.Component;

/**
 * Order 바운디드 컨텍스트에서 Shipping 에 배송 생성을 요청하는 어댑터.
 *
 * <p>Shipping 의 JPA 엔티티·리포지토리를 직접 참조하지 않고 inbound use case 만 호출한다
 * (payment 의 {@code OrderAdapter} 와 같은 방식).
 *
 * <p>값은 같지만 타입이 둘인 이유는 {@link ShippingAddressSnapshot} 의 주석에 있다 —
 * 주문서에 굳은 주소와 배송이 들고 있는(이후 바뀔 수 있는) 주소는 같은 것이 아니다.
 */
@Component
public class ShipmentCreationAdapter implements CreateShipmentPort {

    private final ShippingUseCase shippingUseCase;

    public ShipmentCreationAdapter(ShippingUseCase shippingUseCase) {
        this.shippingUseCase = shippingUseCase;
    }

    @Override
    public void createForOrder(Long orderId, ShippingAddressSnapshot address) {
        shippingUseCase.createForOrder(orderId, new ShippingAddress(
                address.recipientName(), address.phone(), address.postalCode(),
                address.address1(), address.address2(), address.deliveryMemo()));
    }
}
