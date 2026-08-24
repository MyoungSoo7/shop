package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.payment.application.port.out.CancelUnpaidOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort.OrderInfo;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import org.springframework.stereotype.Component;

/**
 * Payment 바운디드 컨텍스트에서 Order 바운디드 컨텍스트에 접근할 때 쓰는 어댑터.
 * Order 의 JPA 엔티티·리포지토리를 직접 참조하지 않고 Order 의 inbound use case 만 호출한다.
 */
@Component
public class OrderAdapter implements LoadOrderPort, UpdateOrderStatusPort, CancelUnpaidOrderPort {

    private final GetOrderUseCase getOrderUseCase;
    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;

    public OrderAdapter(GetOrderUseCase getOrderUseCase,
                        ChangeOrderStatusUseCase changeOrderStatusUseCase) {
        this.getOrderUseCase = getOrderUseCase;
        this.changeOrderStatusUseCase = changeOrderStatusUseCase;
    }

    @Override
    public OrderInfo loadOrder(Long orderId) {
        Order order = getOrderUseCase.getOrderById(orderId);
        return new OrderInfo(
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getStatus().name()
        );
    }

    @Override
    public void updateOrderStatus(Long orderId, String status) {
        changeOrderStatusUseCase.updateStatus(orderId, status);
    }

    @Override
    public boolean cancelUnpaidOrder(Long orderId, String reason) {
        return changeOrderStatusUseCase.cancelUnpaidOrder(orderId, reason);
    }
}
