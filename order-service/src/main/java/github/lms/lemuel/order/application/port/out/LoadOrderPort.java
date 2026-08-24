package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;

import java.util.List;
import java.util.Optional;

/**
 * 주문 조회 Outbound Port
 */
public interface LoadOrderPort {

    Optional<Order> findById(Long orderId);

    List<Order> findByUserId(Long userId);

    List<Order> findByUserId(Long userId, String status, java.time.LocalDateTime from, java.time.LocalDateTime to);

    List<Order> findAll();
}
