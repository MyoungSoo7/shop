package github.lms.lemuel.payment.application.port.out;

import java.util.Optional;

/**
 * paymentId 로부터 셀러 정산 메타(sellerId·tier·cycle)를 해석하는 아웃바운드 포트.
 * order-service 가 자신의 DB(opslab) 안에서 해석한다 — settlement 의 cross-service 조인을 대체.
 */
public interface LoadSellerSettlementMetaPort {

    /** payment→order→product→user 경로로 셀러 메타 해석. 결제/주문/상품 매핑 실패 시 empty. */
    Optional<SellerSettlementMeta> findByPaymentId(Long paymentId);
}
