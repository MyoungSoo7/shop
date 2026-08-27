package github.lms.lemuel.shipping.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 배송 1 건의 추적 타임라인 — 내부 이력 + (있으면) 택배사 스캔을 시간순으로 합친 결과.
 *
 * <p><b>실패가 타임라인을 비우지 못하게 한다.</b> 택배사 조회가 실패했을 때 빈 목록을 돌려주면
 * 화면에는 "이력 없음"이 뜬다 — 사용자에게 그것은 <i>아무 일도 없었다</i>는 뜻이고, 실제로는
 * 출고까지 끝난 주문일 수 있다. 그래서 실패는 목록을 비우는 대신 {@link #carrierNote} 한 줄로
 * 남고, 내부 이력은 그대로 보인다.
 *
 * @param carrierNote 택배사 조회가 <b>실패</b>했을 때의 사유. 연동이 꺼져 있을 뿐이면 {@code null}
 *                    이다 — 쓰지도 않는 연동의 부재를 사용자에게 알릴 이유가 없다
 */
public record ShipmentTimeline(
        Long orderId,
        ShippingStatus status,
        String carrier,
        String trackingNumber,
        List<ShipmentTrackingEvent> events,
        String carrierNote) {

    public ShipmentTimeline {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
        events = List.copyOf(events);
    }

    /**
     * 두 출처를 발생 시각 오름차순으로 합친다.
     *
     * <p>같은 시각이면 내부 이력을 앞에 둔다 — 우리가 출고를 찍은 뒤에 택배사가 집화한 것이
     * 실제 순서이기 때문이다. 중복처럼 보이는 두 줄(내부 '출고 완료' + 택배사 '집화')은 지우지
     * 않는다. 서로 다른 주체가 확인한 서로 다른 사실이고, 출처가 화면에 함께 나가므로 사용자가
     * 구분할 수 있다.
     */
    public static ShipmentTimeline of(Shipment shipment, List<ShipmentTrackingEvent> internal,
                                      List<ShipmentTrackingEvent> carrierScans, String carrierNote) {
        List<ShipmentTrackingEvent> merged = new ArrayList<>(internal);
        merged.addAll(carrierScans);
        merged.sort(Comparator.comparing(ShipmentTrackingEvent::occurredAt)
                .thenComparing(e -> e.source() == TrackingEventSource.INTERNAL ? 0 : 1));
        return new ShipmentTimeline(shipment.getOrderId(), shipment.getStatus(),
                shipment.getCarrier(), shipment.getTrackingNumber(), merged, carrierNote);
    }

    /** 택배사 조회가 실패했는가. 화면이 "정보를 못 불러왔다"를 덧붙일지 판단하는 값. */
    public boolean carrierUnavailable() {
        return carrierNote != null;
    }
}
