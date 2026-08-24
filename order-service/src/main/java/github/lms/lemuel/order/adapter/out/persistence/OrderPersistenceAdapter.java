package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadPendingStockReclaimPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderItemOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Order Persistence Adapter — 다건 주문 (OrderItem) 처리 포함.
 *
 * <p>저장 시: Order 저장 → 부여된 PK 를 자식 OrderItem 에 주입 → 자식들 일괄 저장.
 * 로드 시: Order 도메인 복원 + 자식 OrderItem 들 추가 로딩.
 */
@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements LoadOrderPort, SaveOrderPort, LoadPendingStockReclaimPort {

    private final SpringDataOrderJpaRepository orderJpaRepository;
    private final SpringDataOrderItemRepository orderItemRepository;
    private final SpringDataOrderItemOptionRepository orderItemOptionRepository;
    private final OrderPersistenceMapper mapper;

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findById(orderId)
                .map(this::toDomainWithItems);
    }

    @Override
    public List<Order> findByUserId(Long userId) {
        return orderJpaRepository.findByUserId(userId)
                .stream()
                .map(this::toDomainWithItems)
                .collect(Collectors.toList());
    }

    @Override
    public List<Order> findByUserId(Long userId, String status,
                                    java.time.LocalDateTime from,
                                    java.time.LocalDateTime to) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        return orderJpaRepository.findUserOrders(userId, normalizedStatus, from, to)
                .stream()
                .map(this::toDomainWithItems)
                .collect(Collectors.toList());
    }

    @Override
    public List<Order> findAll() {
        return orderJpaRepository.findAll()
                .stream()
                .map(this::toDomainWithItems)
                .collect(Collectors.toList());
    }

    @Override
    public Order save(Order order) {
        OrderJpaEntity entity = mapper.toEntity(order);
        OrderJpaEntity saved = orderJpaRepository.save(entity);

        // 다건 주문이면 자식 아이템들도 함께 저장
        if (order.isMultiItem()) {
            for (OrderItem item : order.getItems()) {
                OrderItemJpaEntity itemEntity = new OrderItemJpaEntity(
                        item.getId(), saved.getId(), item.getProductId(), item.getVariantId(),
                        item.getSku(), item.getProductName(), item.getUnitPrice(),
                        item.getQuantity(), item.getLineAmount(), item.getCreatedAt(),
                        item.getCanceledAt()   // 부분 취소 표시가 저장에서 유실되면 재기동 후 되살아난다
                );
                OrderItemJpaEntity savedItem = orderItemRepository.save(itemEntity);
                saveItemOptions(savedItem.getId(), item);
            }
        }

        Order result = mapper.toDomain(saved);
        if (order.isMultiItem()) {
            // saved Order 에 자식 아이템 다시 로드해서 부착
            List<OrderItem> reloaded = orderItemRepository.findByOrderIdOrderByIdAsc(saved.getId())
                    .stream()
                    .map(this::toItemDomain)
                    .toList();
            result.replaceItems(reloaded);
        }
        return result;
    }

    @Override
    public List<Order> findAwaitingStockReclaim(int limit) {
        return orderJpaRepository
                .findAwaitingStockReclaim(org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(this::toDomainWithItems)
                .toList();
    }

    @Override
    public List<Order> findStockReclaimCrossedBetween(java.time.LocalDateTime from,
                                                      java.time.LocalDateTime to, int limit) {
        return orderJpaRepository
                .findStockReclaimCrossedBetween(from, to,
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(this::toDomainWithItems)
                .toList();
    }

    private Order toDomainWithItems(OrderJpaEntity entity) {
        Order order = mapper.toDomain(entity);
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(entity.getId())
                .stream()
                .map(this::toItemDomain)
                .toList();
        order.replaceItems(items);
        return order;
    }

    /**
     * 라인 옵션 스냅샷 저장. 라인 자체가 불변이므로 이미 적힌 스냅샷은 다시 쓰지 않는다 —
     * 주문서를 소급해서 바꾸지 않기 위해서다.
     */
    private void saveItemOptions(Long orderItemId, OrderItem item) {
        if (item.getOptions().isEmpty()
                || !orderItemOptionRepository.findByOrderItemIdOrderByAxisSortOrderAsc(orderItemId).isEmpty()) {
            return;
        }
        for (OrderItemOption option : item.getOptions()) {
            orderItemOptionRepository.save(new OrderItemOptionJpaEntity(
                    null, orderItemId, option.getAxisSortOrder(), option.getAxisCode(),
                    option.getAxisName(), option.getValueCode(), option.getValueName()));
        }
    }

    private OrderItem toItemDomain(OrderItemJpaEntity e) {
        List<OrderItemOption> options = orderItemOptionRepository
                .findByOrderItemIdOrderByAxisSortOrderAsc(e.getId())
                .stream()
                .map(o -> OrderItemOption.rehydrate(o.getId(), o.getOrderItemId(),
                        o.getAxisSortOrder(), o.getAxisCode(), o.getAxisName(),
                        o.getValueCode(), o.getValueName()))
                .toList();
        return OrderItem.rehydrate(
                e.getId(), e.getOrderId(), e.getProductId(), e.getVariantId(),
                e.getSku(), e.getProductName(), e.getUnitPrice(),
                e.getQuantity(), e.getLineAmount(), e.getCreatedAt(), options,
                e.getCanceledAt()
        );
    }
}
