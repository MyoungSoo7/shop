package github.lms.lemuel.payment.application.port.out;

/**
 * Port for updating order status in Order bounded context
 */
public interface UpdateOrderStatusPort {
    void updateOrderStatus(Long orderId, String status);
}
