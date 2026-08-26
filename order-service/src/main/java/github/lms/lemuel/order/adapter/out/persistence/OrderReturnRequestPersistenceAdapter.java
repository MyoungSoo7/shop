package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadOrderReturnRequestPort;
import github.lms.lemuel.order.application.port.out.SaveOrderReturnRequestPort;
import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.ReturnRequestStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
public class OrderReturnRequestPersistenceAdapter
        implements SaveOrderReturnRequestPort, LoadOrderReturnRequestPort {

    /** 대기열 한 번에 가져올 최대치의 상한 — 호출자가 더 큰 값을 줘도 여기서 잘린다. */
    private static final int MAX_QUEUE_LIMIT = 500;

    private final SpringDataOrderReturnRequestRepository repository;

    public OrderReturnRequestPersistenceAdapter(SpringDataOrderReturnRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public OrderReturnRequest save(OrderReturnRequest request) {
        // 새 신청이면 새 행, 이미 식별자가 있으면 그 행에 현재 값을 덮어쓴다. merge 대신 로드-후-수정을
        // 쓰는 이유는 detached 엔티티 merge 가 매핑에서 빠진 칸을 조용히 null 로 되돌리기 때문이다.
        OrderReturnRequestJpaEntity entity;
        if (request.getId() == null) {
            entity = OrderReturnRequestJpaEntity.fromDomain(request);
        } else {
            entity = repository.findById(request.getId())
                    .orElseGet(() -> OrderReturnRequestJpaEntity.fromDomain(request));
            entity.applyFrom(request);
        }
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<OrderReturnRequest> findById(Long requestId) {
        return repository.findById(requestId).map(OrderReturnRequestJpaEntity::toDomain);
    }

    @Override
    public Optional<OrderReturnRequest> findOpenByOrderId(Long orderId) {
        return repository.findOpenByOrderId(orderId).map(OrderReturnRequestJpaEntity::toDomain);
    }

    @Override
    public List<OrderReturnRequest> findAllByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByIdDesc(orderId).stream()
                .map(OrderReturnRequestJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<OrderReturnRequest> findByStatuses(Collection<ReturnRequestStatus> statuses, int limit) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        int capped = Math.clamp(limit, 1, MAX_QUEUE_LIMIT);
        List<String> names = statuses.stream().map(Enum::name).toList();
        return repository.findByStatusInOrderByRequestedAtAsc(names, PageRequest.of(0, capped)).stream()
                .map(OrderReturnRequestJpaEntity::toDomain)
                .toList();
    }
}
