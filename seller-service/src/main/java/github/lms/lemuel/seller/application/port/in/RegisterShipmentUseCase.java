package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.domain.SellerScope;

/**
 * 송장(운송장) 등록 — 셀러가 "보냈다" 고 말하는 유일한 경로.
 *
 * <p>여기서도 이 서비스는 남의 원장에 쓰지 않는다. 배송의 소유자는 order-service 이고,
 * 여기서는 요청 한 행을 자기 테이블에 남기고 {@code lemuel.seller.shipment_registered} 로
 * 출고를 요청한다. 실제 상태 전이는 저쪽 {@code ShippingUseCase.ship()} 이 한다.
 *
 * <p><b>한 주문에 한 번뿐이다.</b> 저쪽 출고는 PENDING/READY 에서만 성립하므로 두 번째 요청은
 * 어차피 거절된다. 그 거절이 저쪽에서만 일어나면 셀러 화면에는 "등록됨" 이 남아 서로 다른 두
 * 사실이 두 화면에 걸린다. 그래서 두 번째 등록은 이쪽에서 즉시 거절한다
 * ({@code uq_shipment_request_order}).
 *
 * <p>오등록 정정 경로는 <b>없다</b>. 만들지 않은 이유는 저쪽에 되돌리는 API 가 없어서다.
 * 취소 버튼만 화면에 두면 눌렀을 때 아무 일도 안 일어나고, 셀러는 정정된 줄 안다.
 */
public interface RegisterShipmentUseCase {

    /**
     * @param scope 제출 권한 검사를 함께 통과해야 한다 — STAFF 는 송장을 등록할 수 없다.
     * @param userId 실제로 등록한 사람. 조직 안에서 누가 찍었는지 남긴다.
     * @throws IllegalArgumentException 이미 등록된 주문이거나, 내 셀러의 주문이 아닐 때
     */
    void register(SellerScope scope, long userId, long orderId, String carrier, String trackingNumber);
}
