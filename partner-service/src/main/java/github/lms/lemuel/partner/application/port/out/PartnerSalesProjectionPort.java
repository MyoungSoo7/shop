package github.lms.lemuel.partner.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 결제·환불 프로젝션 쓰기. 둘 다 upsert 이며 서로를 건드리지 않는다(순서 독립). */
public interface PartnerSalesProjectionPort {

    void upsertSale(long paymentId, long orderId, Long sellerId, BigDecimal amount,
                    String sellerTier, String settlementCycle, String paymentMethod,
                    LocalDateTime capturedAt, boolean capturedAtEstimated);

    /**
     * 환불을 <b>결제 행과 무관하게</b> 쌓는다.
     *
     * <p>결제 행을 찾아 금액을 깎지 않는 이유: 환불 이벤트가 결제 이벤트보다 먼저 올 수 있고
     * (토픽이 다르면 순서 보장이 없다), 그때 결제 행은 아직 존재하지 않는다. 여기서 "결제가
     * 없으면 스킵" 하면 그 환불은 영영 반영되지 않는다.
     */
    void upsertRefund(long paymentId, String refundKey, long orderId,
                      BigDecimal refundAmount, BigDecimal refundedTotal);
}
