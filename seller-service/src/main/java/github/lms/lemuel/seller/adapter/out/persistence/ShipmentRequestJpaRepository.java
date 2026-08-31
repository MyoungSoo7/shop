package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 송장 등록 요청 적재. */
interface ShipmentRequestJpaRepository extends JpaRepository<ShipmentRequestJpaEntity, Long> {

    /**
     * {@code DO NOTHING} 이고 {@code DO UPDATE} 가 아니다.
     *
     * <p>재등록을 허용하면 셀러 화면의 송장은 바뀌는데 order-service 의 배송은 첫 요청 그대로
     * 남는다 — {@code ShippingUseCase.ship()} 은 PENDING/READY 에서만 성립하므로 두 번째
     * 요청은 저쪽에서 거절된다. 그러면 <b>같은 주문에 대해 두 화면이 서로 다른 송장번호를</b>
     * 말하게 되고, 그 상태에서 고객 문의가 들어오면 어느 쪽이 진짜인지 알 방법이 없다.
     *
     * <p>그래서 두 번째 등록은 여기서 막고, 갱신된 행 수를 그대로 돌려준다. 0 이면 이미 등록된
     * 주문이고, 서비스는 그 사실을 셀러에게 그대로 말한다. 정정 경로가 없다는 것도 화면에 적는다.
     *
     * @return 실제로 들어간 행 수 — 1 이면 신규, 0 이면 이미 있었다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO seller.seller_shipment_requests
                (order_id, seller_id, carrier, tracking_number, requested_by_user_id, requested_at)
            VALUES
                (:orderId, :sellerId, :carrier, :trackingNumber, :requestedByUserId, NOW())
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") long orderId,
                       @Param("sellerId") long sellerId,
                       @Param("carrier") String carrier,
                       @Param("trackingNumber") String trackingNumber,
                       @Param("requestedByUserId") long requestedByUserId);
}
