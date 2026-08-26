package github.lms.lemuel.order.adapter.in.web.response;

import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.RefundAccount;
import github.lms.lemuel.order.domain.ReturnWaybill;

import java.time.LocalDateTime;

/**
 * 반품·교환 신청 응답.
 *
 * <p><b>계좌 번호는 마스킹해서만 나간다.</b> 이 응답은 고객 마이페이지와 운영 콘솔이 같이 쓰는데,
 * 전체 번호가 필요한 곳은 실제로 송금하는 사람뿐이고 그 조회 경로는 여기가 아니다. 목록 API 가
 * 계좌 원문을 흘리면 신청 100 건을 부르는 것만으로 계좌 100 개가 나간다.
 */
public record ReturnRequestResponse(
        Long id,
        Long orderId,
        Long userId,
        String type,
        String status,
        String reasonCode,
        String reasonDetail,
        String refundBankCode,
        String refundAccountNumberMasked,
        String refundAccountHolder,
        boolean awaitsRefundAccount,
        String returnCarrier,
        String returnTrackingNumber,
        String exchangeCarrier,
        String exchangeTrackingNumber,
        String requestedBy,
        String processedBy,
        String rejectReason,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt,
        LocalDateTime collectedAt,
        LocalDateTime exchangeShippedAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {

    public static ReturnRequestResponse from(OrderReturnRequest request) {
        RefundAccount account = request.getRefundAccount();
        ReturnWaybill returnWaybill = request.getReturnWaybill();
        ReturnWaybill exchangeWaybill = request.getExchangeWaybill();
        return new ReturnRequestResponse(
                request.getId(),
                request.getOrderId(),
                request.getUserId(),
                request.getType().name(),
                request.getStatus().name(),
                request.getReasonCode(),
                request.getReasonDetail(),
                account == null ? null : account.bankCode(),
                account == null ? null : account.maskedAccountNumber(),
                account == null ? null : account.holderName(),
                request.awaitsRefundAccount(),
                returnWaybill == null ? null : returnWaybill.carrier(),
                returnWaybill == null ? null : returnWaybill.trackingNumber(),
                exchangeWaybill == null ? null : exchangeWaybill.carrier(),
                exchangeWaybill == null ? null : exchangeWaybill.trackingNumber(),
                request.getRequestedBy(),
                request.getProcessedBy(),
                request.getRejectReason(),
                request.getRequestedAt(),
                request.getApprovedAt(),
                request.getCollectedAt(),
                request.getExchangeShippedAt(),
                request.getCompletedAt(),
                request.getUpdatedAt());
    }
}
