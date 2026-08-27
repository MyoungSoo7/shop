package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.ShipmentTimeline;

import java.util.Optional;

/** 배송 추적 타임라인 조회. */
public interface GetShipmentTrackingUseCase {

    /**
     * 주문의 배송 타임라인.
     *
     * @return 배송이 아직 없으면 {@link Optional#empty()} — 오류가 아니다(결제 후 배송 생성 전)
     */
    Optional<ShipmentTimeline> getTimeline(Long orderId);
}
