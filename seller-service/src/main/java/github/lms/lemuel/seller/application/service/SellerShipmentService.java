package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.in.RegisterShipmentUseCase;
import github.lms.lemuel.seller.application.port.out.PublishSellerEventPort;
import github.lms.lemuel.seller.application.port.out.SellerOrderQueryPort;
import github.lms.lemuel.seller.application.port.out.ShipmentRequestPort;
import github.lms.lemuel.seller.domain.SellerScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 송장 등록.
 *
 * <p>순서가 이 클래스의 전부다. <b>소유 확인 → 행 남기기 → 발행</b>, 그리고 셋이 한 트랜잭션이다.
 *
 * <ol>
 *   <li>내 셀러의 주문인지 먼저 본다. 이 검사를 건너뛰면 주문번호만 바꿔 남의 주문에 내 송장을
 *       올릴 수 있고, 그건 조회 IDOR 과 달리 <b>남의 주문 상태를 바꾼다</b>.</li>
 *   <li>중복은 유니크 제약이 판정한다. 먼저 조회해 없으면 넣는 방식은 버튼 두 번 누르는 그
 *       순간에 그대로 뚫린다.</li>
 *   <li>발행은 마지막이고 같은 트랜잭션이다. 먼저 발행하면 행이 안 남은 채 출고가 나가고,
 *       셀러 화면에는 아무 흔적이 없다.</li>
 * </ol>
 */
@Service
public class SellerShipmentService implements RegisterShipmentUseCase {

    /** {@code seller_shipment_requests.carrier VARCHAR(50)} 과 짝. */
    private static final int MAX_CARRIER_LENGTH = 50;
    /** {@code seller_shipment_requests.tracking_number VARCHAR(100)} 과 짝. */
    private static final int MAX_TRACKING_LENGTH = 100;

    private final SellerOrderQueryPort orderQueryPort;
    private final ShipmentRequestPort shipmentRequestPort;
    private final PublishSellerEventPort publishPort;
    private final Clock clock;

    public SellerShipmentService(SellerOrderQueryPort orderQueryPort,
                                 ShipmentRequestPort shipmentRequestPort,
                                 PublishSellerEventPort publishPort,
                                 Clock clock) {
        this.orderQueryPort = orderQueryPort;
        this.shipmentRequestPort = shipmentRequestPort;
        this.publishPort = publishPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void register(SellerScope scope, long userId, long orderId, String carrier, String trackingNumber) {
        long sellerId = scope.requireSubmitPermission();
        String safeCarrier = require(carrier, "택배사", MAX_CARRIER_LENGTH);
        String safeTracking = require(trackingNumber, "송장번호", MAX_TRACKING_LENGTH);

        boolean mine = !orderQueryPort
                .findOrders(sellerId, LocalDate.EPOCH, LocalDate.now(clock).plusDays(1), orderId, false, 1, 0L)
                .isEmpty();
        if (!mine) {
            // 없는 주문과 남의 주문을 구분하지 않는다 — 구분하면 주문번호를 훑어 존재 여부를
            // 알아낼 수 있고, 여기서는 그게 남의 매출 규모를 세는 경로가 된다.
            throw new IllegalArgumentException("내 셀러의 주문이 아니거나 아직 결제되지 않은 주문입니다: orderId=" + orderId);
        }

        if (!shipmentRequestPort.record(orderId, sellerId, safeCarrier, safeTracking, userId)) {
            throw new IllegalArgumentException("이미 송장이 등록된 주문입니다: orderId=" + orderId);
        }

        publishPort.shipmentRegistered(orderId, sellerId, safeCarrier, safeTracking);
    }

    private String require(String value, String label, int maxLength) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + "를 입력해 주세요.");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + "는 " + maxLength + "자 이하여야 합니다 (입력 " + trimmed.length() + "자).");
        }
        return trimmed;
    }
}
