package github.lms.lemuel.order.application.port.out;

public interface SaveOrderStatusHistoryPort {
    void save(Long orderId, String previousStatus, String newStatus, String changedBy, String reason);
}
