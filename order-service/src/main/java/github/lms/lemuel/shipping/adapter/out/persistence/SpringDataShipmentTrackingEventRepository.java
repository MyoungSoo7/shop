package github.lms.lemuel.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataShipmentTrackingEventRepository
        extends JpaRepository<ShipmentTrackingEventJpaEntity, Long> {

    /**
     * 발생 시각 오름차순. 같은 시각이면 id 순 — 한 번의 저장에서 두 줄이 같은 시각을 받을 수
     * 있는데, 정렬 기준이 하나뿐이면 화면 순서가 조회마다 달라진다.
     */
    List<ShipmentTrackingEventJpaEntity> findByOrderIdOrderByOccurredAtAscIdAsc(Long orderId);
}
