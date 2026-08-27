package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = OrderStatus.class)
public interface OrderPersistenceMapper {

    /**
     * Entity → Domain 복원. no-arg + setter 대신 {@link Order#rehydrate} 팩토리로만 재구성해
     * 도메인의 상태 전이 봉인을 매퍼가 우회하지 못하게 한다. items 는 어댑터가 별도 로드해 부착.
     */
    default Order toDomain(OrderJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        Order order = Order.rehydrate(
                entity.getId(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getAmount(),
                OrderStatus.fromString(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getShippingFee(),
                entity.isShipped(),
                entity.isStockRestored());
        order.attachShippingAddress(toAddressSnapshot(entity));
        // 묶음 id 는 toEntity 쪽에서는 이름이 같아 MapStruct 가 알아서 옮기지만, 복원은 rehydrate
        // 팩토리를 거치므로 여기서 손으로 붙여야 한다. 빠뜨리면 저장은 되는데 다시 읽으면 사라져,
        // 여러 곳 배송 재요청이 묶음 대신 주문 한 건만 돌려준다.
        order.attachDestinationGroup(entity.getDestinationGroupId());
        return order;
    }

    /**
     * 배송지 스냅샷 컬럼 → VO. 수령인이 비어 있으면 스냅샷 도입 이전의 주문이므로 {@code null} 이다
     * (일부 컬럼만 채워진 상태는 존재하지 않는다 — 쓰기는 항상 VO 통째로 일어난다).
     */
    private static ShippingAddressSnapshot toAddressSnapshot(OrderJpaEntity entity) {
        if (entity.getRecipientName() == null || entity.getRecipientName().isBlank()) {
            return null;
        }
        return new ShippingAddressSnapshot(
                entity.getRecipientName(),
                entity.getRecipientPhone(),
                entity.getPostalCode(),
                entity.getAddress1(),
                entity.getAddress2(),
                entity.getDeliveryMemo());
    }

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    @Mapping(target = "recipientName", source = "shippingAddress.recipientName")
    @Mapping(target = "recipientPhone", source = "shippingAddress.phone")
    @Mapping(target = "postalCode", source = "shippingAddress.postalCode")
    @Mapping(target = "address1", source = "shippingAddress.address1")
    @Mapping(target = "address2", source = "shippingAddress.address2")
    @Mapping(target = "deliveryMemo", source = "shippingAddress.deliveryMemo")
    OrderJpaEntity toEntity(Order domain);
}
