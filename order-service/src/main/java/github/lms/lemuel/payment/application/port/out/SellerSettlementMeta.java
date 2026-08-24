package github.lms.lemuel.payment.application.port.out;

/**
 * 정산에 필요한 셀러 메타 — 결제 발행 시점에 order DB(payments→orders→products→users)에서 해석한다.
 *
 * <p>이 값을 PaymentCaptured 이벤트에 동봉(Event-Carried State Transfer)하면, settlement 가
 * 정산 생성 시 order DB 4테이블 조인을 할 필요가 없어진다(ADR 0020 Phase 1).
 *
 * <p>sellerId/sellerTier/settlementCycle 는 셀러 미할당(seller_id NULL) 시 null 일 수 있다.
 * tier/cycle 은 enum 이름 문자열(예: "VIP", "T_PLUS_3") 그대로 전달 — 도메인 enum 결합을 피한다.
 */
public record SellerSettlementMeta(Long sellerId, String sellerTier, String settlementCycle) {
}
