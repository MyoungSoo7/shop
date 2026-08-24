package github.lms.lemuel.shipping.domain;

/**
 * 배송 상태머신.
 *
 * <pre>
 *   PENDING ──배송지 확정→ READY ──출고→ SHIPPED ──택배사 스캔→ IN_TRANSIT ──→ DELIVERED
 *                                                                              ↘ RETURNED
 * </pre>
 */
public enum ShippingStatus {
    /** 주문 직후 — 배송지 확정 또는 셀러 출고 준비 대기 */
    PENDING,
    /** 출고 준비 완료 — 택배사 픽업 대기 */
    READY,
    /** 셀러 출고 완료 — 운송장 번호 발급됨 */
    SHIPPED,
    /** 택배사가 첫 스캔 (집하). 일반적으로 SHIPPED 직후 */
    IN_TRANSIT,
    /** 수령 완료 */
    DELIVERED,
    /** 반품 처리 완료 */
    RETURNED
}
