package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase.OrderStatusCount;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadPendingStockReclaimPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SearchOrdersPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderItemOption;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Order Persistence Adapter — 다건 주문 (OrderItem) 처리 포함.
 *
 * <p>저장 시: Order 저장 → 부여된 PK 를 자식 OrderItem 에 주입 → 자식들 일괄 저장.
 * 로드 시: Order 도메인 복원 + 자식 OrderItem 들 추가 로딩.
 *
 * <p>관리자 콘솔 조회({@link SearchOrdersPort})도 여기서 구현한다. 조건이 동적이라 SQL 로 읽지만,
 * 도메인 복원은 {@code toDomainWithItems} 하나만 쓰기 위해서다 — 복원 경로가 둘이 되면 한쪽만
 * 고친 날 두 화면이 다른 주문을 보여 준다.
 */
@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter
        implements LoadOrderPort, SaveOrderPort, LoadPendingStockReclaimPort, SearchOrdersPort {

    private final SpringDataOrderJpaRepository orderJpaRepository;
    private final SpringDataOrderItemRepository orderItemRepository;
    private final SpringDataOrderItemOptionRepository orderItemOptionRepository;
    private final OrderPersistenceMapper mapper;
    private final JdbcTemplate jdbcTemplate;

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
    public List<Order> findByDestinationGroupId(String destinationGroupId) {
        // 빈 값으로 부르면 묶음 id 가 없는 주문 전체를 훑을 뻔한 자리다. 그런 조회는 뜻이 없으므로
        // 쿼리를 내보내지 않고 빈 목록으로 끝낸다.
        if (destinationGroupId == null || destinationGroupId.isBlank()) {
            return List.of();
        }
        return orderJpaRepository.findByDestinationGroupIdOrderByIdAsc(destinationGroupId)
                .stream()
                .map(this::toDomainWithItems)
                .collect(Collectors.toList());
    }

    /**
     * 관리자 목록 한 페이지.
     *
     * <p>두 걸음으로 나눈다 — SQL 로 <b>id 만</b> 정렬·페이징해 가져오고, 그 id 들로 도메인을
     * 복원한다. 한 번에 하려면 주문 행과 라인·옵션을 조인해야 하는데, 그러면 라인 수만큼 행이
     * 불어나 {@code LIMIT} 이 "주문 N건"이 아니라 "행 N개"를 뜻하게 된다 — 페이지 크기가
     * 주문마다 달라지고, 그 사실은 화면에서 보이지 않는다.
     *
     * <p>{@code findAllById} 는 순서를 보장하지 않으므로 id 목록 순서로 다시 세운다. 정렬을
     * DB 에서 해 놓고 여기서 잃으면 페이지마다 순서가 흔들린다.
     */
    @Override
    public List<Order> search(OrderCriteria criteria, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);
        args.add(size);
        args.add((long) page * size);

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM opslab.orders" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                Long.class, args.toArray());
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, OrderJpaEntity> byId = new LinkedHashMap<>();
        orderJpaRepository.findAllById(ids).forEach(entity -> byId.put(entity.getId(), entity));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::toDomainWithItems)
                .toList();
    }

    @Override
    public long count(OrderCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM opslab.orders" + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 상태별 건수·금액 합계.
     *
     * <p>{@code COALESCE} 로 감싸는 이유: 금액이 전부 NULL 인 상태 묶음에서 {@code SUM} 은 0 이
     * 아니라 NULL 을 준다. 그대로 내보내면 화면이 "매출 0원"이 아니라 빈 칸을 그린다.
     */
    @Override
    public List<OrderStatusCount> countByStatus(OrderCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        return jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS amount_sum"
                        + " FROM opslab.orders" + where
                        + " GROUP BY status ORDER BY cnt DESC, status ASC",
                (rs, rowNum) -> new OrderStatusCount(
                        rs.getString("status"), rs.getLong("cnt"), rs.getBigDecimal("amount_sum")),
                args.toArray());
    }

    /**
     * 값이 있는 조건만 WHERE 절로 조립하고 같은 순서로 바인딩 인자를 채운다.
     *
     * <p>{@code (? IS NULL OR col = ?)} 관용구를 쓰지 않는 이유는 PostgreSQL 이 그 자리의
     * 파라미터 타입을 추론하지 못해({@code 42P18}) 쿼리 전체가 실행 시점에 터지기 때문이다.
     * 조립되는 것은 상수 조각뿐이고 사용자 입력은 전부 바인딩이다.
     */
    private static String buildWhere(OrderCriteria criteria, List<Object> args) {
        List<String> clauses = new ArrayList<>();

        List<String> statuses = criteria.statuses();
        if (statuses != null && !statuses.isEmpty()) {
            // IN 자리 표시를 개수만큼 만든다. 빈 목록이면 절 자체를 달지 않는다 —
            // SQL 의 `IN ()` 은 문법 오류이고, 빈 목록의 뜻은 "전부"이지 "아무것도"가 아니다.
            clauses.add("status IN (" + String.join(", ", java.util.Collections.nCopies(statuses.size(), "?")) + ")");
            args.addAll(statuses);
        }
        if (criteria.createdFrom() != null) {
            clauses.add("created_at >= ?");
            args.add(Timestamp.valueOf(criteria.createdFrom()));
        }
        if (criteria.createdToExclusive() != null) {
            clauses.add("created_at < ?");
            args.add(Timestamp.valueOf(criteria.createdToExclusive()));
        }

        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
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
                        item.getCanceledAt(),  // 부분 취소 표시가 저장에서 유실되면 재기동 후 되살아난다
                        item.getAllocatedDiscount() // 유실되면 그 라인이 정가로 되돌아가 과환불이 난다
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
                e.getCanceledAt(), e.getAllocatedDiscount()
        );
    }
}
