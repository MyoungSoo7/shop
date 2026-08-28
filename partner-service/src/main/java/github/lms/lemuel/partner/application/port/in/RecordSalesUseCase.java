package github.lms.lemuel.partner.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제·환불 프로젝션 적재.
 *
 * <p>{@code captured} 가 이 서비스의 심장이다 — 셀러 식별자를 실어 오는 이벤트가 이것뿐이라,
 * 여기서 놓친 결제는 어떤 화면에도 영영 나타나지 않는다(원본을 우리가 갖고 있지 않다).
 */
public interface RecordSalesUseCase {

    void captured(SaleCaptured event);

    void refunded(SaleRefunded event);

    /**
     * @param sellerId null 이면 셀러 미할당 결제다. 지우지 않고 담아 두면, 나중에 셀러가 붙어
     *                 같은 {@code paymentId} 로 재발행될 때 그대로 살아난다.
     * @param capturedAt 프로듀서가 존 없는 로컬시각(KST)으로 싣는다. null 이면 수신 시각으로
     *                   대체하고 {@code capturedAtEstimated} 를 세운다.
     * @param capturedAtEstimated 시각을 대체했는가 — 화면이 각주로 알린다
     */
    record SaleCaptured(long paymentId, long orderId, Long sellerId, BigDecimal amount,
                        String sellerTier, String settlementCycle, String paymentMethod,
                        LocalDateTime capturedAt, boolean capturedAtEstimated) {
    }

    /**
     * @param refundKey {@code refundId}(있으면) 또는 {@code event_id}. 같은 환불에 대해 안정적이라
     *                  PK 가 3단 멱등의 마지막 단계로 그대로 작동한다.
     * @param refundAmount 이번 환불액(delta). 계약상 없을 수 있어 0 으로 채운다.
     * @param refundedTotal 누적 환불액. 없으면 null — 실효액은 조회 시점에
     *                      {@code GREATEST(MAX(누적), SUM(delta))} 로 읽는다.
     */
    record SaleRefunded(long paymentId, String refundKey, long orderId,
                        BigDecimal refundAmount, BigDecimal refundedTotal) {
    }
}
