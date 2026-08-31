package github.lms.lemuel.seller.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문·결제·상품 프로젝션 적재 — 셀러 주문 화면을 만드는 다섯 토픽의 도착점.
 *
 * <p>다섯 중 하나만 성격이 다르다. {@link #productRegistered} 는 <b>우리가 낸 요청의 회신</b>이라,
 * 사본을 채우는 데서 끝나지 않고 우리 원본(신청서)의 상태를 마무리한다. 나머지 넷은 순수한 사본
 * 적재이고, 늦게 오거나 영영 안 와도 다른 값이 틀려지지 않는다.
 */
public interface RecordCommerceUseCase {

    /** {@code lemuel.payment.captured} — 셀러에게 주문이 보이는 유일한 경로. */
    void captured(SaleCaptured event);

    /** {@code lemuel.payment.refunded} — 금액보다 <b>상태</b>가 중요하다. 환불된 주문은 출고 대상이 아니다. */
    void refunded(SaleRefunded event);

    /** {@code lemuel.order.created} — 결제 행에 상품·주문상태를 붙여 주는 보조. */
    void orderCreated(OrderCreated event);

    /** {@code lemuel.product.changed} — 상품 ID 대신 이름을 보여주기 위한 것뿐이다. */
    void productChanged(long productId, String name);

    /**
     * {@code lemuel.product.registered} — 승인한 신청서가 <b>실제로</b> 카탈로그에 실렸다는 회신.
     *
     * <p>이 회신이 오기 전까지 신청서는 "승인됨, 상품번호 대기" 다. 승인 시점에 상품번호를
     * 지어내지 않는 이유가 이것이다 — 지어내면 등록이 실패한 건과 성공한 건을 구분할 수 없다.
     */
    void productRegistered(ProductRegistered event);

    /**
     * @param capturedAtEstimated 결제시각이 이벤트에 없어 수신 시각으로 대체한 행
     */
    record SaleCaptured(long paymentId, long orderId, Long sellerId, BigDecimal amount,
                        String paymentMethod, LocalDateTime capturedAt, boolean capturedAtEstimated) {
    }

    record SaleRefunded(long paymentId, String refundKey, long orderId,
                        BigDecimal refundAmount, BigDecimal refundedTotal) {
    }

    record OrderCreated(long orderId, long userId, Long productId, String status,
                        BigDecimal amount, LocalDateTime createdAt) {
    }

    record ProductRegistered(long productId, String name, long submissionId, long sellerId) {
    }
}
