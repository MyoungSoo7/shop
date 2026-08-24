package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;

/**
 * 주문 알림 전송 Port
 */
public interface SendOrderNotificationPort {
    void sendOrderConfirmation(String email, Order order);
}
