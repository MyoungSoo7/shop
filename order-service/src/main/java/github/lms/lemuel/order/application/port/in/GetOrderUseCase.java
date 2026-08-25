package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

import java.util.List;

/**
 * 주문 조회 UseCase (Inbound Port)
 */
public interface GetOrderUseCase {

    Order getOrderById(Long orderId);

    List<Order> getOrdersByUserId(Long userId);

    List<Order> getOrdersByUserId(Long userId, String status, java.time.LocalDateTime from, java.time.LocalDateTime to);

    // getAllOrders() 는 SearchOrdersUseCase.search() 로 대체됐다 — 무페이징 전건 조회.
}
