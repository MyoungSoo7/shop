package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
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
        return Order.rehydrate(
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
    }

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    OrderJpaEntity toEntity(Order domain);
}
