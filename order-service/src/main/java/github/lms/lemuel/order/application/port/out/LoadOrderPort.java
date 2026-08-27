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

    /**
     * 여러 곳 배송 묶음에 속한 주문들 — 주문 id 오름차순(만들어진 순서).
     *
     * <p>멱등 재요청이 이 경로로 돌아온다. 멱등 원장은 키 하나에 주문 id 하나만 담으므로
     * 재요청은 묶음의 첫 주문만 찾아낼 수 있고, 나머지는 그 주문이 들고 있는 묶음 id 로
     * 여기서 되찾는다. 순서가 흔들리면 같은 키로 두 번 부른 응답의 주문 순서가 달라진다.
     */
    List<Order> findByDestinationGroupId(String destinationGroupId);

    // findAll() 은 없다. 전 주문을 한 번에 읽는 경로는 관리자 콘솔이 유일했고, 그 화면은
    // SearchOrdersPort 의 페이지 조회로 옮겼다. 포트에 남겨 두면 다음 사람이 다시 부른다.
}
