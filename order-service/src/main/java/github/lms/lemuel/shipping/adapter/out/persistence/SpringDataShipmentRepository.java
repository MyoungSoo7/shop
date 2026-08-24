package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataShipmentRepository extends JpaRepository<ShipmentJpaEntity, Long> {

    Optional<ShipmentJpaEntity> findByOrderId(Long orderId);

    Optional<ShipmentJpaEntity> findByCarrierAndTrackingNumber(String carrier, String trackingNumber);

    /**
     * 배송 지연 임계를 <b>이번 스캔 창에서 막 넘어선</b> IN_TRANSIT 배송 — 운영 관제 shipping.delayed 신호용.
     *
     * <p>{@code shippedAt} 이 (지연 임계 경계) 직전 창에 든 것만 잡아 배송당 대략 1회만 신호가 나가게 한다
     * (매 스캔마다 같은 지연 건을 재발행하는 카운트 부풀림 방지).
     */
    @Query("""
            select s from ShipmentJpaEntity s
            where s.status = :status
              and s.shippedAt <= :crossedBefore
              and s.shippedAt > :crossedAfter
            """)
    List<ShipmentJpaEntity> findNewlyDelayed(@Param("status") ShippingStatus status,
                                             @Param("crossedBefore") LocalDateTime crossedBefore,
                                             @Param("crossedAfter") LocalDateTime crossedAfter);
}
