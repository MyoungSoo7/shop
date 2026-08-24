package github.lms.lemuel.order.adapter.out.shipping;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort;
import org.springframework.stereotype.Component;

/**
 * shipping 이 선언한 {@link RestoreReturnedOrderStockPort} 를 order 슬라이스가 구현한다 —
 * 반품 회수가 확인되면 주문 쪽 재고를 되돌린다.
 *
 * <p>이 클래스가 <b>order 쪽에 사는 이유</b>: shipping 슬라이스에 두면 shipping→order 간선이 생기고,
 * 주문 생성·부분취소가 배송비 산정({@code AssessShippingFeeUseCase})을 부르는 order→shipping 과 만나
 * {@code order ↔ shipping} 순환이 된다. 능력(주문 상태 변경)을 가진 쪽이 구현을 제공하면 결합은
 * order→shipping 한 방향으로 모인다.
 */
@Component
public class ReturnedOrderStockAdapter implements RestoreReturnedOrderStockPort {

    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;

    public ReturnedOrderStockAdapter(ChangeOrderStatusUseCase changeOrderStatusUseCase) {
        this.changeOrderStatusUseCase = changeOrderStatusUseCase;
    }

    @Override
    public void restoreReturnedOrderStock(Long orderId) {
        changeOrderStatusUseCase.restoreStockOnReturn(orderId);
    }
}
