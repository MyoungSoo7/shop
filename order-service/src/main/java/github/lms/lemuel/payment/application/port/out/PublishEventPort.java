package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Port for publishing domain events (Transactional Outbox 경유).
 */
public interface PublishEventPort {
    void publishPaymentCreated(Long paymentId, Long orderId);
    void publishPaymentAuthorized(Long paymentId);

    /**
     * 결제 매입 완료. 컨슈머가 정산 생성에 amount 를 사용한다.
     * sellerMeta(sellerId·tier·cycle)를 동봉하면 settlement 가 정산 생성 시 order DB 조인을
     * 하지 않아도 된다(ADR 0020 Phase 1, Event-Carried State Transfer). 미해석 시 null 허용.
     */
    void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount,
                                java.time.LocalDateTime capturedAt,
                                String paymentMethod, String pgTransactionId,
                                SellerSettlementMeta sellerMeta);

    /**
     * 환불. refundedAmount(누적)는 settlement 결제 프로젝션의 환불액·상태 갱신에(ADR 0020 Phase 3b-4),
     * refundAmount(이번 환불 건별 금액)·refundId 는 settlement 역정산(netAmount 재계산·원장 역분개)에 쓰인다.
     *
     * @param refundedAmount 이 환불 반영 후 누적 환불액
     * @param refundAmount   이번 환불 1건의 금액 (delta)
     * @param refundId       환불 엔티티 ID (분할결제 등 Refund 미생성 경로는 null 허용)
     */
    void publishPaymentRefunded(Long paymentId, Long orderId, BigDecimal refundedAmount,
                                BigDecimal refundAmount, Long refundId);
}
