package github.lms.lemuel.seller.application.port.out;

import github.lms.lemuel.seller.domain.ProductSubmission;

/**
 * 이 서비스가 바깥에 말을 거는 유일한 통로 — Transactional Outbox.
 *
 * <p>두 이벤트 모두 <b>요청</b>이지 통보가 아니다. 상품 등록도 출고도 order-service 가 하고,
 * 여기서는 "해 달라" 를 같은 트랜잭션에서 outbox 에 적을 뿐이다. 그래서 실패는 이쪽 화면이 아니라
 * 저쪽에서 드러난다 — 회신 이벤트가 안 오는 것으로.
 */
public interface PublishSellerEventPort {

    /** {@code lemuel.seller.product_approved} — 카탈로그에 상품을 만들어 달라. */
    void productApproved(ProductSubmission submission);

    /** {@code lemuel.seller.shipment_registered} — 이 주문을 출고 처리해 달라. */
    void shipmentRegistered(long orderId, long sellerId, String carrier, String trackingNumber);
}
