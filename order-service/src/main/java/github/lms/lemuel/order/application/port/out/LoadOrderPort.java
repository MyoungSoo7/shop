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

    // findAll() 은 없다. 전 주문을 한 번에 읽는 경로는 관리자 콘솔이 유일했고, 그 화면은
    // SearchOrdersPort 의 페이지 조회로 옮겼다. 포트에 남겨 두면 다음 사람이 다시 부른다.
}
