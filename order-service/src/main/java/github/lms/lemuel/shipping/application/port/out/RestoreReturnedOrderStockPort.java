package github.lms.lemuel.shipping.application.port.out;

/**
 * 반품 회수 완료 시 주문 재고 원복 포트 — shipping 컨텍스트가 order 컨텍스트에 요청한다.
 *
 * <p>배송 후 환불은 물건이 고객 손에 있어 재고를 되돌리지 않는다. 물건이 실제로 돌아온 것이 확인되는
 * 유일한 지점이 반품 회수이며, 여기서 비로소 재고가 판매 가능 상태로 복귀한다.
 *
 * <p>원복 멱등은 order 도메인이 보장한다(이미 원복된 주문이면 no-op).
 */
public interface RestoreReturnedOrderStockPort {

    void restoreReturnedOrderStock(Long orderId);
}
