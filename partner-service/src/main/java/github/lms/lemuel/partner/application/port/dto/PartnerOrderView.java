package github.lms.lemuel.partner.application.port.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문(정확히는 <b>결제</b>) 한 건.
 *
 * <p>행의 기준이 결제인 이유는 이 서비스가 셀러를 아는 유일한 경로가
 * {@code payment.captured} 이기 때문이다(ADR 0020). 결제되지 않은 주문은 여기에 나타나지
 * 않는다 — 계약의 한계이고, 화면에도 그렇게 적는다.
 *
 * @param orderStatus {@code order.created} 가 실어 온 주문 상태. 아직 안 왔으면 null 이다.
 *                    "CREATED" 로 기본값을 넣지 않는 이유는, 모르는 상태를 아는 척하면 취소된
 *                    주문이 정상으로 보이기 때문이다.
 * @param capturedAtEstimated 결제시각이 이벤트에 없어 수신 시각으로 대체된 행
 */
public record PartnerOrderView(
        long orderId,
        long paymentId,
        LocalDateTime capturedAt,
        boolean capturedAtEstimated,
        BigDecimal amount,
        BigDecimal refundedAmount,
        BigDecimal netAmount,
        String paymentMethod,
        String orderStatus,
        Long productId,
        String productName) {
}
