package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;

import java.util.List;

/** 배송 추적 이력 적재. */
public interface SaveShipmentTrackingEventPort {

    /**
     * 여러 줄을 한 번에 적재한다. 빈 목록은 정상이며 아무것도 하지 않는다
     * (상태가 바뀌지 않은 저장 — 예: 같은 값으로 다시 저장 — 에서는 남길 일이 없다).
     */
    List<ShipmentTrackingEvent> saveAll(List<ShipmentTrackingEvent> events);
}
