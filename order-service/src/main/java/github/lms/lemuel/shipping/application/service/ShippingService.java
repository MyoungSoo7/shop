package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.application.port.out.RestoreReturnedOrderStockPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentPort;
import github.lms.lemuel.shipping.application.port.out.SaveShipmentTrackingEventPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ShippingService implements ShippingUseCase {

    private final LoadShipmentPort loadPort;
    private final SaveShipmentPort savePort;
    private final RestoreReturnedOrderStockPort restoreStockPort;
    private final github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase safetyNumberUseCase;
    private final SaveShipmentTrackingEventPort saveTrackingEventPort;

    public ShippingService(LoadShipmentPort loadPort, SaveShipmentPort savePort,
                           RestoreReturnedOrderStockPort restoreStockPort,
                           github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase safetyNumberUseCase,
                           SaveShipmentTrackingEventPort saveTrackingEventPort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.restoreStockPort = restoreStockPort;
        this.safetyNumberUseCase = safetyNumberUseCase;
        this.saveTrackingEventPort = saveTrackingEventPort;
    }

    /**
     * 배송 저장 + 그 전이가 남긴 추적 이력 적재를 한 몸으로 묶는다.
     *
     * <p>모든 전이가 이 한 곳을 지나가게 해서 "상태는 바뀌었는데 이력은 없는" 경우를 만들지
     * 않는다. 같은 트랜잭션 안이므로 저장이 롤백되면 이력도 함께 사라진다 — 일어나지 않은 일이
     * 이력에 남는 쪽이, 이력이 없는 쪽보다 나쁘다.
     */
    private Shipment persist(Shipment shipment) {
        Shipment saved = savePort.save(shipment);
        saveTrackingEventPort.saveAll(shipment.drainPendingEvents());
        return saved;
    }

    @Override
    public Shipment createForOrder(Long orderId, ShippingAddress address) {
        loadPort.loadByOrderId(orderId).ifPresent(s -> {
            throw new ShipmentInvariantViolationException("이미 배송이 생성된 주문: " + orderId);
        });
        Shipment saved = persist(Shipment.createPending(orderId, address));
        // 배송이 생기는 순간 안심번호를 붙인다 — 기사·판매자에게 실번호가 나가기 전에 확보해야 한다.
        // 풀이 말라 비어 있어도 배송 생성을 실패시키지 않는다(주문 흐름 전체가 멈춘다). WARN 은 서비스가 남긴다.
        safetyNumberUseCase.assignForOrder(orderId);
        return saved;
    }

    @Override
    public Shipment changeAddress(Long orderId, ShippingAddress newAddress) {
        Shipment s = mustExist(orderId);
        s.changeAddress(newAddress);
        return persist(s);
    }

    @Override
    public Shipment ship(Long orderId, String carrier, String trackingNumber) {
        Shipment s = mustExist(orderId);
        s.ship(carrier, trackingNumber);
        return persist(s);
    }

    @Override
    public Shipment markInTransit(Long orderId) {
        Shipment s = mustExist(orderId);
        s.markInTransit();
        return persist(s);
    }

    @Override
    public Shipment markDelivered(Long orderId) {
        Shipment s = mustExist(orderId);
        s.markDelivered();
        return persist(s);
    }

    @Override
    public Shipment markReturned(Long orderId) {
        Shipment s = mustExist(orderId);
        s.returnShipment();
        Shipment saved = persist(s);
        // 물건이 실제로 돌아온 것이 확인되는 유일한 지점 — 배송 후 환불로 보류됐던 재고를 여기서 되돌린다.
        // 이미 원복된 주문이면 order 도메인이 no-op 처리한다(멱등).
        restoreStockPort.restoreReturnedOrderStock(orderId);
        return saved;
    }

    private Shipment mustExist(Long orderId) {
        return loadPort.loadByOrderId(orderId)
                .orElseThrow(() -> new ShipmentInvariantViolationException("배송 없음: orderId=" + orderId));
    }
}
