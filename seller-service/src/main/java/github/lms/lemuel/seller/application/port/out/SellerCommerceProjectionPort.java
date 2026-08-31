package github.lms.lemuel.seller.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제·환불·주문·상품 프로젝션 쓰기. 전부 upsert 이며 서로를 건드리지 않는다(순서 독립).
 *
 * <p>토픽이 다르면 순서 보장이 없다는 사실이 이 인터페이스 모양을 거의 다 정한다. 어떤 메서드도
 * "다른 행이 이미 있을 것" 을 전제하지 않는다.
 */
public interface SellerCommerceProjectionPort {

    void upsertSale(long paymentId, long orderId, Long sellerId, BigDecimal amount,
                    String paymentMethod, LocalDateTime capturedAt, boolean capturedAtEstimated);

    /**
     * 환불을 <b>결제 행과 무관하게</b> 쌓는다.
     *
     * <p>결제 행을 찾아 금액을 깎지 않는 이유: 환불 이벤트가 결제 이벤트보다 먼저 올 수 있고,
     * 그때 결제 행은 아직 존재하지 않는다. 여기서 "결제가 없으면 스킵" 하면 그 환불은 영영
     * 반영되지 않는다.
     */
    void upsertRefund(long paymentId, String refundKey, long orderId,
                      BigDecimal refundAmount, BigDecimal refundedTotal);

    void upsertOrder(long orderId, long userId, Long productId, String status,
                     BigDecimal amount, LocalDateTime orderedAt);

    /** 상품 이름만 갱신. 신청서 연결은 건드리지 않는다({@code COALESCE}). */
    void upsertProduct(long productId, String name);

    /**
     * 상품 사본에 "이 상품은 이 신청서에서 나왔다" 를 기록한다.
     *
     * <p>이 메서드는 사본({@code seller_products})만 건드린다. 신청서 원장의
     * {@code product_id} 를 채우는 것은 {@link ProductSubmissionPort} 쪽 일이다 — 한 메서드가
     * 원본과 사본을 함께 쓰면, 나중에 사본을 재구축할 때 원본까지 덮어쓰게 된다.
     */
    void linkProduct(long productId, String name, long submissionId);
}
