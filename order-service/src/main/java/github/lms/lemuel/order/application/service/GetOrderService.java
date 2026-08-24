package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문 조회 서비스
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetOrderService implements GetOrderUseCase {

    private final LoadOrderPort loadOrderPort;

    @Override
    public Order getOrderById(Long orderId) {
        return loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Override
    public List<Order> getOrdersByUserId(Long userId) {
        return loadOrderPort.findByUserId(userId);
    }

    @Override
    public List<Order> getOrdersByUserId(Long userId, String status,
                                         java.time.LocalDateTime from,
                                         java.time.LocalDateTime to) {
        return loadOrderPort.findByUserId(userId, status, from, to);
    }

    @Override
    public List<Order> getAllOrders() {
        return loadOrderPort.findAll();
    }
}
