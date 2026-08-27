package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;

import java.util.List;

/** 배송 추적 이력 조회. */
public interface LoadShipmentTrackingEventPort {

    /** 발생 시각 오름차순. 이력이 없으면 빈 목록(옛 주문은 이력이 없을 수 있다). */
    List<ShipmentTrackingEvent> loadByOrderId(Long orderId);
}
