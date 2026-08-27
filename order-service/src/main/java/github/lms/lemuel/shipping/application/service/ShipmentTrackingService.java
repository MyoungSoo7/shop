package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.GetShipmentTrackingUseCase;
import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentTrackingEventPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShipmentTimeline;
import github.lms.lemuel.shipping.domain.ShipmentTrackingEvent;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import github.lms.lemuel.shipping.domain.TrackingEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 배송 추적 타임라인 조립.
 *
 * <p>세 가지를 지킨다.
 *
 * <ol>
 *   <li><b>내부 이력이 정본이다.</b> 택배사 연동이 꺼져 있어도 타임라인은 성립한다.</li>
 *   <li><b>실패가 목록을 비우지 못한다.</b> 택배사 조회 실패는 사유 한 줄로 붙고 내부 이력은 남는다.
 *       빈 목록은 화면에서 "아무 일도 없었다"로 읽히고, 그건 출고까지 끝난 주문에 대해 거짓이다.</li>
 *   <li><b>이력이 없는 옛 배송도 비워 두지 않는다.</b> 이 기능이 생기기 전에 만들어진 배송에는
 *       이벤트가 한 줄도 없다. 그때는 배송 자체가 들고 있는 시각(생성·출고·완료)으로 최소한의
 *       타임라인을 합성한다 — 소급 적재(backfill)로 없는 사실을 지어내는 대신, 실제로 남아 있는
 *       값만 쓴다.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class ShipmentTrackingService implements GetShipmentTrackingUseCase {

    private static final Logger log = LoggerFactory.getLogger(ShipmentTrackingService.class);

    private final LoadShipmentPort loadShipmentPort;
    private final LoadShipmentTrackingEventPort loadEventPort;
    private final CarrierTrackingPort carrierTrackingPort;

    public ShipmentTrackingService(LoadShipmentPort loadShipmentPort,
                                   LoadShipmentTrackingEventPort loadEventPort,
                                   CarrierTrackingPort carrierTrackingPort) {
        this.loadShipmentPort = loadShipmentPort;
        this.loadEventPort = loadEventPort;
        this.carrierTrackingPort = carrierTrackingPort;
    }

    @Override
    public Optional<ShipmentTimeline> getTimeline(Long orderId) {
        return loadShipmentPort.loadByOrderId(orderId).map(this::assemble);
    }

    private ShipmentTimeline assemble(Shipment shipment) {
        List<ShipmentTrackingEvent> internal = loadEventPort.loadByOrderId(shipment.getOrderId());
        if (internal.isEmpty()) {
            internal = synthesizeFrom(shipment);
        }

        CarrierTrackingPort.Result result = fetchCarrierScans(shipment);
        if (result == null) {
            // 연동이 꺼져 있다 — 사용자에게 알릴 일이 아니다.
            return ShipmentTimeline.of(shipment, internal, List.of(), null);
        }
        if (!result.available()) {
            return ShipmentTimeline.of(shipment, internal, List.of(), result.unavailableReason());
        }
        return ShipmentTimeline.of(shipment, internal, toEvents(shipment.getOrderId(), result.scans()), null);
    }

    /** @return 연동이 꺼져 있거나 조회할 운송장이 없으면 {@code null} */
    private CarrierTrackingPort.Result fetchCarrierScans(Shipment shipment) {
        String trackingNumber = shipment.getTrackingNumber();
        if (trackingNumber == null || trackingNumber.isBlank() || !carrierTrackingPort.enabled()) {
            return null;
        }
        try {
            return carrierTrackingPort.fetch(shipment.getCarrier(), trackingNumber);
        } catch (RuntimeException e) {
            // 포트 규약은 "예외를 던지지 않는다"지만, 규약을 어긴 구현 하나가 배송 조회 전체를
            // 500 으로 만들게 두지는 않는다. 사용자는 내부 이력을 그대로 보고, 사고는 로그로 남는다.
            log.warn("택배사 배송 조회 중 예외: orderId={}, cause={}", shipment.getOrderId(), e.toString());
            return CarrierTrackingPort.Result.unavailable("택배사 배송 정보를 불러오지 못했습니다.");
        }
    }

    private static List<ShipmentTrackingEvent> toEvents(Long orderId, List<CarrierTrackingPort.Scan> scans) {
        List<ShipmentTrackingEvent> events = new ArrayList<>(scans.size());
        for (CarrierTrackingPort.Scan scan : scans) {
            events.add(ShipmentTrackingEvent.carrier(orderId, scan.status(), scan.description(),
                    scan.location(), scan.occurredAt()));
        }
        return events;
    }

    /**
     * 이력이 없는 배송의 최소 타임라인. 배송 레코드가 실제로 들고 있는 시각만 쓴다.
     *
     * <p>{@code shippedAt}·{@code deliveredAt} 이 없으면 그 줄은 만들지 않는다 — 시각을 모르는
     * 사건에 그럴듯한 시각을 붙이는 순간, 타임라인은 사실 기록이 아니라 추정이 된다.
     */
    private static List<ShipmentTrackingEvent> synthesizeFrom(Shipment shipment) {
        Long orderId = shipment.getOrderId();
        List<ShipmentTrackingEvent> events = new ArrayList<>(3);
        if (shipment.getCreatedAt() != null) {
            events.add(ShipmentTrackingEvent.rehydrate(null, orderId, ShippingStatus.PENDING,
                    TrackingEventSource.INTERNAL,
                    "주문이 접수되어 배송 준비를 시작합니다.", null, shipment.getCreatedAt()));
        }

        LocalDateTime shippedAt = shipment.getShippedAt();
        if (shippedAt != null) {
            String carrier = shipment.getCarrier();
            events.add(ShipmentTrackingEvent.rehydrate(null, orderId, ShippingStatus.SHIPPED,
                    TrackingEventSource.INTERNAL,
                    (carrier == null || carrier.isBlank() ? "택배사" : carrier) + "에 상품을 인계했습니다.",
                    null, shippedAt));
        }
        LocalDateTime deliveredAt = shipment.getDeliveredAt();
        if (deliveredAt != null) {
            events.add(ShipmentTrackingEvent.rehydrate(null, orderId, ShippingStatus.DELIVERED,
                    TrackingEventSource.INTERNAL,
                    "상품이 배송지에 도착했습니다.", null, deliveredAt));
        }
        return events;
    }
}
