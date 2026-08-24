package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;

public interface SaveOrderPort {
    Order save(Order order);
}
