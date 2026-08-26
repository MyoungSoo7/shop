package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.ReturnRequestStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 반품·교환 신청 조회. */
public interface LoadOrderReturnRequestPort {

    Optional<OrderReturnRequest> findById(Long requestId);

    /** 그 주문에 아직 처리 중인 신청(REQUESTED·APPROVED·COLLECTED)이 있으면 돌려준다. */
    Optional<OrderReturnRequest> findOpenByOrderId(Long orderId);

    /** 그 주문의 신청 전부 — 최근 것이 앞. 거절 뒤 재신청이 정상 흐름이라 여러 건이 남는다. */
    List<OrderReturnRequest> findAllByOrderId(Long orderId);

    /** 운영 대기열 — 상태로 걸러 오래된 순으로. */
    List<OrderReturnRequest> findByStatuses(Collection<ReturnRequestStatus> statuses, int limit);
}
