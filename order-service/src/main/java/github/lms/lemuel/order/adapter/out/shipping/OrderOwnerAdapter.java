package github.lms.lemuel.order.adapter.out.shipping;

import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.shipping.application.port.out.LoadOrderOwnerPort;
import org.springframework.stereotype.Component;

/**
 * shipping 이 선언한 {@link LoadOrderOwnerPort} 를 order 슬라이스가 구현한다 — 배송 조회·배송지
 * 변경의 소유권 검사에 쓸 "이 주문의 주인" 을 알려 준다.
 *
 * <p>{@link ReturnedOrderStockAdapter} 와 같은 이유로 <b>order 쪽에 산다</b>: shipping 슬라이스에
 * 두면 shipping→order 간선이 생기고, 주문 생성이 배송비 산정을 부르는 order→shipping 과 만나
 * {@code order ↔ shipping} 순환이 된다(ArchUnit 의 슬라이스 순환 규칙이 실제로 잡는다).
 *
 * <p>Order 의 JPA 엔티티·리포지토리를 직접 참조하지 않고 inbound use case 만 호출한다.
 *
 * <p>없는 주문은 예외가 아니라 {@code null} 로 번역한다 — 호출부(소유권 검사)는 "없는 주문"과
 * "남의 주문"을 <b>같은 응답</b>으로 처리해야 한다. 둘을 구분해 응답하면 주문 번호를 훑어
 * 어떤 번호가 실재하는지 알아낼 수 있다.
 */
@Component
public class OrderOwnerAdapter implements LoadOrderOwnerPort {

    private final GetOrderUseCase getOrderUseCase;

    public OrderOwnerAdapter(GetOrderUseCase getOrderUseCase) {
        this.getOrderUseCase = getOrderUseCase;
    }

    @Override
    public Long findOwnerUserId(Long orderId) {
        try {
            return getOrderUseCase.getOrderById(orderId).getUserId();
        } catch (OrderNotFoundException e) {
            return null;
        }
    }
}
